package org.ratmuds.minenav.client.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ratmuds.minenav.client.AStar3D;
import org.ratmuds.minenav.client.CubeData;
import org.ratmuds.minenav.client.MinenavClient;
import org.ratmuds.minenav.client.PathGridSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

@Mixin(Player.class)
public class InteractionMixin {
    private static int tickCounter = 0;
    private static List<int[]> path;
    private static Vec3 pathOrigin;
    private static AtomicBoolean isCalculating = new AtomicBoolean(false);

    private static Vec3 lastBridgePos = null;
    private static int bridgeNoMoveTicks = 0;
    private static boolean bridgeFallbackActive = false;
    private static BlockPos lastSolidBlockBelow = null;

    private static final int RECALC_INTERVAL_TICKS = 20;
    private static final int CALC_TIMEOUT_TICKS = 120;
    private static final int GRID_TTL_TICKS = 40;
    private static final int MAX_GRID_CELLS = 350_000;
    private static final int MAX_EXPANDED_NODES = 250_000;

    private static long nextRecalcGameTime = 0;
    private static int failedRecalcCount = 0;

    private static BlockPos lastRequestedStart = null;
    private static BlockPos lastRequestedEnd = null;

    private static PathGridSnapshot cachedGrid = null;
    private static CompletableFuture<List<int[]>> inFlight = null;
    private static long calcStartedGameTime = 0;
    private static final AtomicInteger calcRequestId = new AtomicInteger(0);

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        tickCounter++;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return;
        Player player = mc.player;
        ClientLevel level = mc.level;

        updateLastSolidBlockBelow(player, level);

        // Get the client instance
        MinenavClient client = MinenavClient.getInstance();
        if (!client.isNavigating()) {
            resetNavigationState();
            client.clearHudNavigationState();
            client.updatePathfindingState(false, false, false, false);
            return;
        }

        // Get effective start and end
        Vec3 start = player.position();
        Vec3 end = client.getEndPos();

        if (end == null) {
            // No end set, can't navigate
            updateHudState(client);
            client.updatePathfindingState(false, false, false, false);
            return;
        }

        // Check if player near end
        if (start.distanceTo(end) < 0.5) {
            client.setNavigating(false);
            client.updatePathfindingState(false, false, false, false);
            player.displayClientMessage(Component.literal("Completed pathfinding!"), true);
            return;
        }

        long gameTime = level.getGameTime();
        maybeTimeoutPathfinding(player, gameTime);
        maybeShowRecalculating(player);
        maybeStartPathfinding(mc, player, level, client, start, end, gameTime);
        followPath(mc, player, level, client);
        updateHudState(client);
    }

    private static void updateLastSolidBlockBelow(Player player, ClientLevel level) {
        if (!player.onGround()) return;
        BlockPos posBelow = player.getBlockPosBelowThatAffectsMyMovement();
        if (!(level.getBlockState(posBelow).getBlock() instanceof AirBlock)) {
            lastSolidBlockBelow = posBelow;
        }
    }

    private static void resetNavigationState() {
        path = null;
        pathOrigin = null;
        lastRequestedStart = null;
        lastRequestedEnd = null;
        failedRecalcCount = 0;
        nextRecalcGameTime = 0;
        cachedGrid = null;
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
        isCalculating.set(false);
    }

    private static void maybeTimeoutPathfinding(Player player, long gameTime) {
        if (!isCalculating.get() || inFlight == null) return;
        if ((gameTime - calcStartedGameTime) <= CALC_TIMEOUT_TICKS) return;

        inFlight.cancel(true);
        inFlight = null;
        isCalculating.set(false);
        failedRecalcCount++;
        nextRecalcGameTime = gameTime + backoffTicks(failedRecalcCount);
        player.displayClientMessage(Component.literal("Pathfinding timed out; retrying soon..."), true);
    }

    private static void maybeShowRecalculating(Player player) {
        if (!isCalculating.get()) return;
        if (tickCounter % 10 != 0) return;
        player.displayClientMessage(Component.literal("Recalculating path..."), true);
    }

    private static void maybeStartPathfinding(Minecraft mc, Player player, ClientLevel level, MinenavClient client, Vec3 start, Vec3 end, long gameTime) {
        BlockPos startBlock = player.blockPosition();
        BlockPos endBlock = BlockPos.containing(end);

        boolean startOrEndChanged = lastRequestedStart == null
                || lastRequestedEnd == null
                || !lastRequestedStart.equals(startBlock)
                || !lastRequestedEnd.equals(endBlock);

        boolean needsPath = path == null || pathOrigin == null || path.size() < 2;
        boolean canRecalcNow = !isCalculating.get()
                && inFlight == null
                && gameTime >= nextRecalcGameTime
                && (tickCounter % RECALC_INTERVAL_TICKS == 0);

        if (!canRecalcNow || (!needsPath && !startOrEndChanged)) return;

        double horizontalPadding = 24.0;
        double verticalPadding = 12.0;

        int minX = (int) Math.floor(Math.min(start.x, end.x) - horizontalPadding);
        int maxX = (int) Math.ceil(Math.max(start.x, end.x) + horizontalPadding);
        int minY = (int) Math.floor(Math.min(start.y, end.y) - verticalPadding) - 2; // extra for "below" checks
        int maxY = (int) Math.ceil(Math.max(start.y, end.y) + verticalPadding) + 2;  // extra for headroom
        int minZ = (int) Math.floor(Math.min(start.z, end.z) - horizontalPadding);
        int maxZ = (int) Math.ceil(Math.max(start.z, end.z) + horizontalPadding);

        int sizeX = Math.max(maxX - minX + 1, 1);
        int sizeY = Math.max(maxY - minY + 1, 1);
        int sizeZ = Math.max(maxZ - minZ + 1, 1);

        long cells = (long) sizeX * (long) sizeY * (long) sizeZ;
        if (cells > MAX_GRID_CELLS) {
            failedRecalcCount++;
            nextRecalcGameTime = gameTime + backoffTicks(failedRecalcCount);
            player.displayClientMessage(Component.literal("Search area too large (" + cells + " cells); move closer to target."), true);
            return;
        }

        lastRequestedStart = startBlock;
        lastRequestedEnd = endBlock;

        PathGridSnapshot grid = cachedGrid;
        boolean reuseGrid = grid != null
                && grid.matches(minX, minY, minZ, sizeX, sizeY, sizeZ)
                && (gameTime - grid.builtGameTime) <= GRID_TTL_TICKS;

        if (!reuseGrid) {
            grid = buildGridSnapshot(level, minX, minY, minZ, sizeX, sizeY, sizeZ, (int) cells, gameTime);
            cachedGrid = grid;
        }

        Vec3 gridOrigin = new Vec3(grid.minX, grid.minY, grid.minZ);
        final PathGridSnapshot gridForCalc = grid;
        final Vec3 gridOriginForCalc = gridOrigin;
        final long gameTimeForCalc = gameTime;

        int startX = clamp(startBlock.getX() - grid.minX, 0, grid.sizeX - 1);
        int startY = clamp(startBlock.getY() - grid.minY, 0, grid.sizeY - 1);
        int startZ = clamp(startBlock.getZ() - grid.minZ, 0, grid.sizeZ - 1);
        int endX = clamp(endBlock.getX() - grid.minX, 0, grid.sizeX - 1);
        int endY = clamp(endBlock.getY() - grid.minY, 0, grid.sizeY - 1);
        int endZ = clamp(endBlock.getZ() - grid.minZ, 0, grid.sizeZ - 1);

        int requestId = calcRequestId.incrementAndGet();
        isCalculating.set(true);
        calcStartedGameTime = gameTimeForCalc;

        inFlight = CompletableFuture.supplyAsync(() -> {
            double[] costs = gridForCalc.costs;
            if (costs == null) {
                costs = buildCosts(gridForCalc.isAir, gridForCalc.sizeX, gridForCalc.sizeY, gridForCalc.sizeZ);
                gridForCalc.costs = costs;
            }
            return AStar3D.findPath(
                    costs,
                    gridForCalc.sizeX, gridForCalc.sizeY, gridForCalc.sizeZ,
                    startX, startY, startZ,
                    endX, endY, endZ,
                    MAX_EXPANDED_NODES
            );
        }).whenCompleteAsync((newPath, ex) -> {
            if (requestId != calcRequestId.get()) return;

            inFlight = null;
            isCalculating.set(false);

            if (ex != null) {
                if (!(ex instanceof CancellationException) && !(ex instanceof CompletionException && ex.getCause() instanceof CancellationException)) {
                    failedRecalcCount++;
                    nextRecalcGameTime = gameTimeForCalc + backoffTicks(failedRecalcCount);
                    player.displayClientMessage(Component.literal("Pathfinding failed; retrying soon..."), true);
                }
                return;
            }

            path = newPath; // cutLShapeCorners(newPath);
            pathOrigin = gridOriginForCalc;

            if (path == null) {
                failedRecalcCount++;
                nextRecalcGameTime = gameTimeForCalc + backoffTicks(failedRecalcCount);
                client.clearCubes();
                player.displayClientMessage(Component.literal("No path found; retrying soon..."), true);
                return;
            }

            // Trim path that is too close to the player now to prevent jittering back and forth
            while (path.size() >= 2 && getNextNodePos().distanceTo(player.position()) < 0.5) {
                path.removeFirst();
            }

            failedRecalcCount = 0;
            nextRecalcGameTime = 0;
            renderPathCubes(client, path, pathOrigin);
        }, mc);
    }

    private static PathGridSnapshot buildGridSnapshot(ClientLevel level, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, int cells, long gameTime) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        byte[] isAir = new byte[cells];
        int idx = 0;
        for (int x = 0; x < sizeX; x++) {
            int worldX = minX + x;
            for (int y = 0; y < sizeY; y++) {
                int worldY = minY + y;
                for (int z = 0; z < sizeZ; z++) {
                    int worldZ = minZ + z;
                    mutable.set(worldX, worldY, worldZ);
                    BlockState state = level.getBlockState(mutable);
                    isAir[idx++] = (byte) (state.getBlock() instanceof AirBlock ? 1 : 0);
                }
            }
        }
        return new PathGridSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, gameTime, isAir);
    }

    private static void renderPathCubes(MinenavClient client, List<int[]> path, Vec3 pathOrigin) {
        List<CubeData> cubes = new ArrayList<>();
        for (int[] coords : path) {
            float worldX = (float) (pathOrigin.x + coords[0]);
            float worldY = (float) (pathOrigin.y + coords[1]);
            float worldZ = (float) (pathOrigin.z + coords[2]);
            cubes.add(CubeData.create(worldX, worldY, worldZ, 1f, 0f, 0f, 1f, 0.75f));
        }
        client.clearCubes();
        client.renderCubes(cubes);
    }

    private static void followPath(Minecraft mc, Player player, ClientLevel level, MinenavClient client) {
        if (path == null || pathOrigin == null || path.size() < 2) {
            client.updatePathfindingState(false, false, false, false);
            return;
        }

        Vec3 nextNodePos = getNextNodePos();
        if (player.onGround() && player.blockPosition().equals(BlockPos.containing(nextNodePos))) {
            path.removeFirst();

            // Rerender cubes
            client.clearCubes();
            renderPathCubes(client, path, pathOrigin);
        }

        if (path.size() < 2) {
            client.updatePathfindingState(false, false, false, false);
            player.displayClientMessage(Component.literal("Reached end of current path..."), true);
            return;
        }

        BlockPos target = BlockPos.containing(getNextNodePos());
        int waypointsLeft = path.size();

        if (shouldDigDown(player, level, target)) {
            resetBridgeState();
            client.updatePathfindingState(false, false, false, false);
            doDigDown(mc, player, "Digging block down... (" + waypointsLeft + " waypoints left)");
            return;
        }

        if (shouldDigUp(player, level, target)) {
            resetBridgeState();
            client.updatePathfindingState(false, false, false, false);
            doDigUp(mc, player, "Digging block up... (" + waypointsLeft + " waypoints left)");
            return;
        }

        if (shouldBridgeTo(level, target, player)) {
            boolean shouldPillarUp = shouldPillarUpWhileBridging(player);
            doBridging(mc, player, level, client, target, waypointsLeft, shouldPillarUp);
            return;
        }

        doWalkingAndPillaring(mc, player, level, client, target, waypointsLeft);
    }

    private static List<int[]> cutLShapeCorners(List<int[]> original) {
        if (original == null || original.size() < 3) return original;

        List<int[]> current = original;
        boolean changed;
        do {
            changed = false;
            List<int[]> out = new ArrayList<>(current.size());
            out.add(current.getFirst());

            for (int i = 1; i < current.size() - 1; i++) {
                int[] a = out.getLast();
                int[] b = current.get(i);
                int[] c = current.get(i + 1);

                if (isLShapeCorner(a, b, c)) {
                    changed = true;
                    continue;
                }
                out.add(b);
            }

            out.add(current.getLast());
            current = out;
        } while (changed && current.size() >= 3);

        return current;
    }

    private static boolean isLShapeCorner(int[] a, int[] b, int[] c) {
        int dx1 = b[0] - a[0];
        int dy1 = b[1] - a[1];
        int dz1 = b[2] - a[2];
        int dx2 = c[0] - b[0];
        int dy2 = c[1] - b[1];
        int dz2 = c[2] - b[2];

        if (manhattan(dx1, dy1, dz1) != 1) return false;
        if (manhattan(dx2, dy2, dz2) != 1) return false;

        // Must turn on a different axis (not continue straight).
        if ((dx1 != 0 && dx2 != 0) || (dy1 != 0 && dy2 != 0) || (dz1 != 0 && dz2 != 0)) return false;

        int dx = c[0] - a[0];
        int dy = c[1] - a[1];
        int dz = c[2] - a[2];

        // a and c must be diagonally adjacent (move 1 in exactly two axes).
        return manhattan(dx, dy, dz) == 2
                && Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) == 1;
    }

    private static int manhattan(int dx, int dy, int dz) {
        return Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
    }

    private static Vec3 getNextNodePos() {
        return new Vec3(
                pathOrigin.x + path.get(1)[0],
                pathOrigin.y + path.get(1)[1],
                pathOrigin.z + path.get(1)[2]
        );
    }

    private static boolean shouldBridgeTo(ClientLevel level, BlockPos target, Player player) {
        // Check if the path block (which could be floating) actually is above us and should be pillared
        if (target.getY() >= player.getY() + 0.5f) return false;

        if (level.getBlockState(target.below()).getBlock() instanceof AirBlock) {
            // Also check if the block below the air is solid (no bridge needed)
            if (!(level.getBlockState(target.below(2)).getBlock() instanceof AirBlock)) return false;

            return true;
        }

        return false;
    }

    private static boolean shouldPillarUpWhileBridging(Player player) {
        if (path == null || pathOrigin == null) return false;
        if (path.size() <= 3) return false;

        // Check if we could just jump
        double nextNextY = pathOrigin.y + path.get(2)[1];
        if (nextNextY <= player.getY() + 1.25f) return false;

        Vec3 nextNodePos = getNextNodePos();
        float distSqr = new Vec2((float) nextNodePos.x, (float) nextNodePos.z)
                .distanceToSqr(new Vec2((float) player.position().x, (float) player.position().z));
        double dy = nextNodePos.y - player.getY();

        // Check if we should actually still bridge out a little bit
        if (Math.abs(dy) < 0.001 || distSqr > 0.65f) {
            return false;
        }

        return nextNextY > player.getY();
    }

    private static boolean shouldDigDown(Player player, ClientLevel level, BlockPos target) {
        if (player.getY() <= target.getY()) return false;

        Vec3 nextNodePos = getNextNodePos();
        float distSqr = new Vec2((float) nextNodePos.x, (float) nextNodePos.z)
                .distanceToSqr(new Vec2((float) player.position().x, (float) player.position().z));

        // Check if we actually should dig down
        if (distSqr > 0.65f) {
            return false;
        }

        return !(level.getBlockState(target).getBlock() instanceof AirBlock);
    }

    private static boolean shouldDigUp(Player player, ClientLevel level, BlockPos target) {
        Vec3 nextNodePos = getNextNodePos();
        float distSqr = new Vec2((float) nextNodePos.x, (float) nextNodePos.z)
                .distanceToSqr(new Vec2((float) player.position().x, (float) player.position().z));

        // Check if we actually should dig up
        if (distSqr > 0.65f) {
            return false;
        }

        return !(level.getBlockState(target.above(2)).getBlock() instanceof AirBlock);
    }

    private static boolean shouldDig(Player player, ClientLevel level, BlockPos target) {
        return !(level.getBlockState(target).getBlock() instanceof AirBlock) || !(level.getBlockState(target.above()).getBlock() instanceof AirBlock);
    }

    private static void resetMovementKeys(Minecraft mc) {
        mc.options.keyUse.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(false);
        mc.options.keyAttack.setDown(false);
    }

    private static void turnPlayerToward(Player player, BlockPos target) {
        double dx = target.getX() + 0.5 - player.position().x;
        double dz = target.getZ() + 0.5 - player.position().z;
        double degrees = -Math.toDegrees(Math.atan2(dx, dz));
        player.setYRot(wrapYawDegrees(degrees));
    }

    private static void doWalkingAndPillaring(Minecraft mc, Player player, ClientLevel level, MinenavClient client, BlockPos target, int waypointsLeft) {
        turnPlayerToward(player, target);

        mc.options.keyUp.setDown(true);
        resetMovementKeys(mc);
        client.updatePathfindingState(false, false, false, false);

        if (shouldJumpUp(player, level, target)) {
            resetBridgeState();
            client.updatePathfindingState(false, true, true, false);
            doPillarUp(mc, player, "Jumping... (" + waypointsLeft + " waypoints left)");
            return;
        }

        if (shouldPlaceUnderneath(player, level, target)) {
            resetBridgeState();
            client.updatePathfindingState(false, false, false, true);
            doPlaceUnderneath(mc, player, "Placing block underneath... (" + waypointsLeft + " waypoints left)");
            return;
        }

        if (shouldDig(player, level, target)) {
            resetBridgeState();
            client.updatePathfindingState(false, false, false, false);
            doBreakBlocks(mc, player, target, level, "Breaking blocks... (" + waypointsLeft + " waypoints left)");
            return;
        }

        player.displayClientMessage(Component.literal("Navigating... (" + waypointsLeft + " waypoints left)"), true);

        if (player.getRotationVector().x == 90) {
            player.setXRot(0);
        }

        boolean shouldBridgeSameLevel = player.onGround()
                && (level.getBlockState(target.below()).getBlock() instanceof AirBlock)
                && (level.getBlockState(target.subtract(new Vec3i(0, 2, 0))).getBlock() instanceof AirBlock);
        if (shouldBridgeSameLevel) {
            boolean shouldPillarUp = shouldPillarUpWhileBridging(player);
            doBridging(mc, player, level, client, target, waypointsLeft, shouldPillarUp);
        } else {
            resetBridgeState();
        }
    }

    private static boolean shouldJumpUp(Player player, ClientLevel level, BlockPos target) {
        // Check if we can jump
        boolean blockAbove = !(level.getBlockState(target.above(2)).getBlock() instanceof AirBlock) || !(level.getBlockState(target.above()).getBlock() instanceof AirBlock);
        if (blockAbove) return false;

        return player.position().y < target.getY();
    }

    private static boolean shouldPlaceUnderneath(Player player, ClientLevel level, BlockPos target) {
        if (player.onGround()) return false;
        if (lastSolidBlockBelow == null) return false;

        boolean canJumpToTarget = lastSolidBlockBelow.getY() + 1.25 >= target.getY();
        boolean targetHasSupport = !(level.getBlockState(target.below()).getBlock() instanceof AirBlock);
        if (canJumpToTarget && targetHasSupport) return false;

        // Only attempt placing if we're actually over air and have a valid "base" block to place onto.
        if (!(level.getBlockState(player.getBlockPosBelowThatAffectsMyMovement()).getBlock() instanceof AirBlock)) return false;
        if (!(level.getBlockState(lastSolidBlockBelow.above()).getBlock() instanceof AirBlock)) return false;

        return true;
    }

    private static void doPillarUp(Minecraft mc, Player player, String statusMessage) {
        player.displayClientMessage(Component.literal(statusMessage), true);
        setPillarUpControls(mc, player);
    }

    private static void setPillarUpControls(Minecraft mc, Player player) {
        player.setXRot(90);
        mc.options.keyJump.setDown(true);
        mc.options.keyUse.setDown(true);
        mc.options.keyShift.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyUp.setDown(false);
    }

    private static void doPlaceUnderneath(Minecraft mc, Player player, String statusMessage) {
        player.displayClientMessage(Component.literal(statusMessage), true);
        if (lastSolidBlockBelow != null) {
            aimAtBlockTop(player, lastSolidBlockBelow);
        } else {
            player.setXRot(90);
        }
        mc.options.keyUse.setDown(true);
        mc.options.keyShift.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyAttack.setDown(false);
    }

    private static void doDigDown(Minecraft mc, Player player, String statusMessage) {
        player.displayClientMessage(Component.literal(statusMessage), true);
        aimAtBlockTop(player, lastSolidBlockBelow);
        aimPitchAtBlockTop(player, lastSolidBlockBelow);

        mc.options.keyUse.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyAttack.setDown(true);
    }

    private static void doDigUp(Minecraft mc, Player player, String statusMessage) {
        player.displayClientMessage(Component.literal(statusMessage), true);
        aimAtBlockTop(player, lastSolidBlockBelow.above(2));
        player.setXRot(-90);

        mc.options.keyUse.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyAttack.setDown(true);
    }

    private static void doBreakBlocks(Minecraft mc, Player player, BlockPos target, ClientLevel level, String statusMessage) {
        BlockPos pos = null;

        if (!(level.getBlockState(target).getBlock() instanceof AirBlock)) {
            pos = target;
        } else if (!(level.getBlockState(target.above()).getBlock() instanceof AirBlock)) {
            pos = target.above();
        }

        if (pos == null) return;

        player.displayClientMessage(Component.literal(statusMessage), true);
        aimAtBlockTop(player, pos);
        aimPitchAtBlockTop(player, pos);

        mc.options.keyUse.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyAttack.setDown(true);
    }

    private static void resetBridgeState() {
        lastBridgePos = null;
        bridgeNoMoveTicks = 0;
        bridgeFallbackActive = false;
    }

    private static void doBridging(Minecraft mc, Player player, ClientLevel level, MinenavClient client, BlockPos target, int waypointsLeft, boolean shouldPillarUp) {
        turnPlayerToward(player, target);
        float bridgeYaw = wrapYawDegrees(player.getYRot() + 180.0);
        player.setYRot(bridgeYaw);

        client.updatePathfindingState(true, shouldPillarUp, shouldPillarUp, false);

        // Bridging controls
        mc.options.keyShift.setDown(true);
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(true);
        mc.options.keyJump.setDown(false);
        mc.options.keyUse.setDown(true);

        BlockPos posBelow = player.getBlockPosBelowThatAffectsMyMovement();
        BlockState stateBelow = level.getBlockState(posBelow);
        Block blockBelow = stateBelow.getBlock();

        if (!(blockBelow instanceof AirBlock)) {
            lastSolidBlockBelow = posBelow;
        }

        if (lastSolidBlockBelow != null) {
            aimPitchAtBlockTop(player, lastSolidBlockBelow);
        } else {
            player.setXRot(80);
        }

        String blockName = blockBelow.getDescriptionId();
        if (shouldPillarUp) {
            MutableComponent message = Component.literal("Building up from ")
                    .append(Component.literal(blockName))
                    .append(Component.literal("... (" + waypointsLeft + " waypoints left)"));
            player.displayClientMessage(message, true);
            setPillarUpControls(mc, player);
            return;
        }

        MutableComponent message = Component.literal("Bridging from ")
                .append(Component.literal(blockName))
                .append(Component.literal("... (" + waypointsLeft + " waypoints left)"));
        player.displayClientMessage(message, true);

        updateBridgeFallback(player, posBelow);
    }

    private static void updateBridgeFallback(Player player, BlockPos posBelow) {
        Vec3 currentPos = player.position();

        if (lastBridgePos != null) {
            double movedX = currentPos.x - lastBridgePos.x;
            double movedZ = currentPos.z - lastBridgePos.z;
            double horizontalMoved = Math.sqrt((movedX * movedX) + (movedZ * movedZ));
            if (horizontalMoved < 0.005) {
                bridgeNoMoveTicks++;
            } else {
                bridgeNoMoveTicks = 0;
                bridgeFallbackActive = false;
            }
        }
        lastBridgePos = currentPos;

        if (!bridgeFallbackActive && bridgeNoMoveTicks >= 20) {
            bridgeFallbackActive = true;
            player.displayClientMessage(Component.literal("Bridge fallback activated (stuck ~1s)"), true);
        }

        if (!bridgeFallbackActive) return;

        double dx = (posBelow.getX() + 0.5) - player.position().x;
        double dz = (posBelow.getZ() + 0.5) - player.position().z;
        double fallbackDegrees = -Math.toDegrees(Math.atan2(dx, dz));
        player.setYRot(wrapYawDegrees(fallbackDegrees));
        player.setXRot(ThreadLocalRandom.current().nextInt(75, 85));
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static int backoffTicks(int failures) {
        int capped = Math.min(failures, 6);
        return 20 * (1 << capped);
    }

    private static int index(int sizeY, int sizeZ, int x, int y, int z) {
        return (x * sizeY + y) * sizeZ + z;
    }

    private static double[] buildCosts(byte[] isAir, int sizeX, int sizeY, int sizeZ) {
        double[] costs = new double[sizeX * sizeY * sizeZ];
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    int i = index(sizeY, sizeZ, x, y, z);
                    if (isAir[i] == 1) {
                        double cost = 1.0;
                        if (y - 1 >= 0 && isAir[index(sizeY, sizeZ, x, y - 1, z)] == 1) {
                            cost += 10.0;
                            if (y - 2 >= 0 && isAir[index(sizeY, sizeZ, x, y - 2, z)] == 1) {
                                cost += 30.0;
                            }
                        }
                        costs[i] = cost;
                    } else {
                        costs[i] = 50.0;
                    }
                }
            }
        }
        return costs;
    }

    private static float wrapYawDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped < 0) wrapped += 360.0;
        return (float) wrapped;
    }

    private static void aimAtBlockTop(Player player, BlockPos target) {
        Vec3 eyePos = player.getEyePosition();
        double targetX = target.getX() + 0.5;
        double targetY = target.getY() + 0.95;
        double targetZ = target.getZ() + 0.5;

        double dx = targetX - eyePos.x;
        double dy = targetY - eyePos.y;
        double dz = targetZ - eyePos.z;

        double horizontal = Math.sqrt((dx * dx) + (dz * dz));
        double yaw = -Math.toDegrees(Math.atan2(dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, horizontal));

        player.setYRot(wrapYawDegrees(yaw));
        player.setXRot((float) pitch);
    }

    private static void aimPitchAtBlockTop(Player player, BlockPos target) {
        Vec3 eyePos = player.getEyePosition();
        double targetX = target.getX() + 0.5;
        double targetY = target.getY() + 0.95;
        double targetZ = target.getZ() + 0.5;

        double dx = targetX - eyePos.x;
        double dy = targetY - eyePos.y;
        double dz = targetZ - eyePos.z;

        double horizontal = Math.sqrt((dx * dx) + (dz * dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        player.setXRot((float) pitch);
    }

    private static void updateHudState(MinenavClient client) {
        boolean calculating = isCalculating.get();
        int waypointsLeft = (path == null) ? 0 : Math.max(path.size() - 1, 0);
        BlockPos nextTarget = null;
        if (path != null && pathOrigin != null && path.size() >= 2) {
            nextTarget = BlockPos.containing(
                    pathOrigin.x + path.get(1)[0],
                    pathOrigin.y + path.get(1)[1],
                    pathOrigin.z + path.get(1)[2]
            );
        }
        client.updateHudNavigationState(calculating, waypointsLeft, nextTarget);
    }
}
