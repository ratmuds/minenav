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
import org.ratmuds.minenav.client.CubeData;
import org.ratmuds.minenav.client.MinenavClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;

import static org.ratmuds.minenav.client.AStar3D.findPath;

class Action {
    public static final int WALK = 0;
    public static final int JUMP = 1;
    public static final int PILLAR = 2;
    public static final int BRIDGE = 3;
    public static final int DROP = 4;

    public Vec3i pos;
    public int action;

    public Action(Vec3i pos, int action) {
        this.pos = pos;
        this.action = action;
    }
}

@Mixin(Player.class)
public class InteractionMixin {
    private static int tickCounter = 0;
    private static List<int[]> path;
    private static List<Action> actionPath;
    private static Vec3 pathOrigin;
    private static AtomicBoolean isCalculating = new AtomicBoolean(false);

    private static Vec3 lastBridgePos = null;
    private static int bridgeNoMoveTicks = 0;
    private static boolean bridgeFallbackActive = false;
    private static BlockPos lastSolidBlockBelow = null;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        tickCounter++;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return;
        Player player = mc.player;
        ClientLevel level = mc.level;

        if (player.onGround()) {
            BlockPos posBelow = player.getBlockPosBelowThatAffectsMyMovement();
            BlockState stateBelow = level.getBlockState(posBelow);
            if (!(stateBelow.getBlock() instanceof AirBlock)) {
                lastSolidBlockBelow = posBelow;
            }
        }

        // Get the client instance
        MinenavClient client = MinenavClient.getInstance();
        if (!client.isNavigating()) {
            path = null;
            actionPath = null;
            pathOrigin = null;
            return;
        }

        // Get effective start and end
        Vec3 start = player.position();
        Vec3 end = client.getEndPos();

        if (end == null) {
            // No end set, can't navigate
            return;
        }

        // Check if player near end
        if (start.distanceTo(end) < 1.75) {
            client.setNavigating(false);
            player.displayClientMessage(Component.literal("Completed pathfinding!"), true);
            return;
        }

        // Automatic Boundaries
        double padding = 20.0;
        Vec3 pathFindBoundStart = new Vec3(
                Math.min(start.x, end.x) - padding,
                Math.min(start.y, end.y) - padding,
                Math.min(start.z, end.z) - padding
        );
        Vec3 pathFindBoundEnd = new Vec3(
                Math.max(start.x, end.x) + padding,
                Math.max(start.y, end.y) + padding, // Extended Y range
                Math.max(start.z, end.z) + padding
        );

        if (tickCounter % 5 == 0 && !isCalculating.get()) {
            int originX = (int) Math.floor(pathFindBoundStart.x);
            int originY = (int) Math.floor(pathFindBoundStart.y);
            int originZ = (int) Math.floor(pathFindBoundStart.z);
            int maxX = (int) Math.floor(pathFindBoundEnd.x);
            int maxY = (int) Math.floor(pathFindBoundEnd.y);
            int maxZ = (int) Math.floor(pathFindBoundEnd.z);

            int sizeX = Math.max(maxX - originX + 1, 1);
            int sizeY = Math.max(maxY - originY + 1, 1);
            int sizeZ = Math.max(maxZ - originZ + 1, 1);

            // Setup costs
            double[][][] costs = new double[sizeX][sizeY][sizeZ];

            // Generate costs on main thread (safe block access)
            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    for (int z = 0; z < sizeZ; z++) {
                        // Convert array indices to world coordinates
                        int worldX = originX + x;
                        int worldY = originY + y;
                        int worldZ = originZ + z;

                        // Fetch block in that position
                        Block block = level.getBlockState(new BlockPos(worldX, worldY, worldZ)).getBlock();

                        if (block instanceof AirBlock) {
                            costs[x][y][z] = 1.0;  // Use small positive cost, not 0

                            // Check if block is floating (no solid block beneath)
                            if (level.getBlockState(new BlockPos(worldX, worldY - 1, worldZ)).getBlock() instanceof AirBlock) {
                                costs[x][y][z] += 10.0; // Heavily penalize walking on air

                                // Check if block is high up in the air
                                if (level.getBlockState(new BlockPos(worldX, worldY - 2, worldZ)).getBlock() instanceof AirBlock) {
                                    costs[x][y][z] += 100.0;
                                }
                            }

                            /*if (worldY < player.position().y) {
                                costs[x][y][z] += 10.0;
                            }*/
                        } else {
                            costs[x][y][z] = Double.POSITIVE_INFINITY;  // Use infinity for unwalkable
                        }
                    }
                }
            }

            // Convert world coordinates to array indices for pathfinding
            int startX = (int) Math.floor(start.x) - originX;
            int startY = (int) Math.floor(start.y) - originY;
            int startZ = (int) Math.floor(start.z) - originZ;
            int endX = (int) Math.floor(end.x) - originX;
            int endY = (int) Math.floor(end.y) - originY;
            int endZ = (int) Math.floor(end.z) - originZ;

            // Start async calculation
            isCalculating.set(true);
            Vec3 newPathOrigin = new Vec3(originX, originY, originZ);
            CompletableFuture
                    .supplyAsync(() -> findPath(costs, startX, startY, startZ, endX, endY, endZ))
                    .handleAsync((newPath, ex) -> {
                        isCalculating.set(false);
                        if (ex != null) {
                            path = null;
                            actionPath = null;
                            pathOrigin = null;
                            player.displayClientMessage(Component.literal("Pathfinding error: " + ex.getClass().getSimpleName()), true);
                            return null;
                        }

                        path = newPath;
                        pathOrigin = newPathOrigin;

                        if (path == null) {
                            actionPath = null;
                            client.clearCubes();
                            return null;
                        }

                        List<CubeData> cubes = new ArrayList<>();
                        List<Action> newActionPath = new ArrayList<>();
                        for (int i = 0; i < path.size(); i++) {
                            int[] coords = path.get(i);

                            int action = Action.WALK;
                            if (i < path.size() - 1) {
                                int[] next = path.get(i + 1);
                                int dx = next[0] - coords[0];
                                int dy = next[1] - coords[1];
                                int dz = next[2] - coords[2];

                                if (dy > 0) {
                                    action = (dx == 0 && dz == 0) ? Action.PILLAR : Action.JUMP;
                                } else if (dy < 0) {
                                    action = Action.DROP;
                                } else {
                                    BlockPos nextWorld = new BlockPos(
                                            (int) (pathOrigin.x + next[0]),
                                            (int) (pathOrigin.y + next[1]),
                                            (int) (pathOrigin.z + next[2])
                                    );
                                    if (level.getBlockState(nextWorld.below()).getBlock() instanceof AirBlock) {
                                        action = Action.BRIDGE;
                                    }
                                }
                            }

                            float worldX = (float) (pathOrigin.x + coords[0]);
                            float worldY = (float) (pathOrigin.y + coords[1]);
                            float worldZ = (float) (pathOrigin.z + coords[2]);
                            float[] rgb = colorForAction(action);
                            cubes.add(CubeData.create(worldX, worldY, worldZ, 1f, rgb[0], rgb[1], rgb[2], 0.75f));

                            newActionPath.add(new Action(new Vec3i(coords[0], coords[1], coords[2]), action));
                        }

                        actionPath = newActionPath;
                        client.clearCubes();
                        client.renderCubes(cubes);
                        return null;
                    }, mc);
        }

        if (path == null || pathOrigin == null || path.size() < 2) return;

        // Check if we should advance to the next path
        Vec3 nextNodePos = new Vec3(
                pathOrigin.x + path.get(1)[0] + 0.5,
                pathOrigin.y + path.get(1)[1],
                pathOrigin.z + path.get(1)[2] + 0.5
        );
        if (nextNodePos.distanceTo(player.position()) < 1.5) {
            path.remove(0);
        }

        if (path.size() < 2) {
            player.displayClientMessage(Component.literal("Reached end of current path..."), true);
            return;
        }

        // Re-evaluate next node after potential removal
        nextNodePos = new Vec3(
                pathOrigin.x + path.get(1)[0] + 0.5,
                pathOrigin.y + path.get(1)[1],
                pathOrigin.z + path.get(1)[2] + 0.5
        );

        // Navigate autonomously using the path
        BlockPos pos = new BlockPos(
                (int) Math.floor(nextNodePos.x),
                (int) Math.floor(nextNodePos.y),
                (int) Math.floor(nextNodePos.z)
        );

        // Rotate player
        double dx = pos.getX() + 0.5 - player.position().x;
        double dz = pos.getZ() + 0.5 - player.position().z;
        double degrees = -Math.toDegrees(Math.atan2(dx, dz));
        player.setYRot(wrapYawDegrees(degrees));

        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyUse.setDown(false);

        // Check if jump needed
        if (player.onGround() && player.position().y < pos.getY()) {
            mc.options.keyUp.setDown(true);
            mc.options.keyJump.setDown(true);
            player.setXRot(90);
            mc.options.keyUse.setDown(true);

            player.displayClientMessage(Component.literal("Jumping... (" + path.size() + " waypoints left)"), true);
        } else if (!player.onGround() && Math.abs(player.position().y - pos.getY()) > 0.5) {
            mc.options.keyUp.setDown(true);
            if (lastSolidBlockBelow != null) {
                aimAtBlockTop(player, lastSolidBlockBelow);
            } else {
                player.setXRot(90);
            }
            mc.options.keyUse.setDown(true);

            player.displayClientMessage(Component.literal("Placing block underneath... (" + path.size() + " waypoints left)"), true);
        } else {
            mc.options.keyUp.setDown(true);
            if (player.getXRot() > 80.0f) {
                player.setXRot(0);
            }

            // Check if we need to bridge (floating blocks on same Y level)
            if (player.onGround() && level.getBlockState(pos.below()).getBlock() instanceof AirBlock) {
                float bridgeYaw = wrapYawDegrees(degrees + 180.0);
                player.setYRot(bridgeYaw);

                // Bridging requires a very unique control scheme :)
                mc.options.keyShift.setDown(true);
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(true);
                mc.options.keyJump.setDown(false);
                mc.options.keyUse.setDown(true);

                // Get block player is standing on
                BlockPos posBelow = player.getBlockPosBelowThatAffectsMyMovement();
                BlockState stateBelow = level.getBlockState(posBelow);
                Block blockBelow = stateBelow.getBlock();

                if (!(blockBelow instanceof AirBlock)) {
                    lastSolidBlockBelow = posBelow;
                }

                if (lastSolidBlockBelow != null) {
                    aimPitchAtBlockTop(player, lastSolidBlockBelow);
                } else {
                    player.setXRot(ThreadLocalRandom.current().nextInt(80, 90));
                }

                String blockName = blockBelow.getDescriptionId();
                MutableComponent message = Component.literal("Bridging from ").append(Component.literal(blockName)).append(Component.literal("... (" + path.size() + " waypoints left)"));
                player.displayClientMessage(message, true);

                // Look slightly below block
                Vec3 currentPos = player.position();

                // Bridge fallback check
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

                if (bridgeFallbackActive) {
                    dx = (posBelow.getX() + 0.5) - player.position().x;
                    dz = (posBelow.getZ() + 0.5) - player.position().z;
                    double fallbackDegrees = -Math.toDegrees(Math.atan2(dx, dz));
                    player.setYRot(wrapYawDegrees(fallbackDegrees));
                    player.setXRot(ThreadLocalRandom.current().nextInt(75, 90));
                }
            } else {
                lastBridgePos = null;
                bridgeNoMoveTicks = 0;
                bridgeFallbackActive = false;
                player.displayClientMessage(Component.literal("Navigating... (" + path.size() + " waypoints left)"), true);
            }
        }
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

    private static float[] colorForAction(int action) {
        return switch (action) {
            case Action.WALK -> new float[]{0.15f, 0.85f, 0.15f};   // green
            case Action.JUMP -> new float[]{1.0f, 0.9f, 0.1f};      // yellow
            case Action.PILLAR -> new float[]{0.9f, 0.2f, 0.95f};   // purple
            case Action.BRIDGE -> new float[]{0.2f, 0.55f, 1.0f};   // blue
            case Action.DROP -> new float[]{1.0f, 0.55f, 0.15f};    // orange
            default -> new float[]{1.0f, 1.0f, 1.0f};               // white
        };
    }
}
