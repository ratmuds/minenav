package org.ratmuds.minenav.client.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.WorldData;
import org.ratmuds.minenav.client.AStar3D;
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

import static org.ratmuds.minenav.client.AStar3D.findPath;

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
            
            // Calculate array dimensions (must be at least 1 in each dimension)
            int sizeX = (int) Math.abs(pathFindBoundEnd.x - pathFindBoundStart.x);
            int sizeY = (int) Math.abs(pathFindBoundEnd.y - pathFindBoundStart.y);
            int sizeZ = (int) Math.abs(pathFindBoundEnd.z - pathFindBoundStart.z);

            // Ensure a minimum size of 1 for each dimension
            sizeX = Math.max(sizeX, 1);
            sizeY = Math.max(sizeY, 1);
            sizeZ = Math.max(sizeZ, 1);

            // Setup costs
            double[][][] costs = new double[sizeX][sizeY][sizeZ];
            
            // Generate costs on main thread (safe block access)
            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    for (int z = 0; z < sizeZ; z++) {
                        // Convert array indices to world coordinates
                        int worldX = (int) pathFindBoundStart.x + x;
                        int worldY = (int) pathFindBoundStart.y + y;
                        int worldZ = (int) pathFindBoundStart.z + z;

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
            int startX = (int) (start.x - pathFindBoundStart.x);
            int startY = (int) (start.y - pathFindBoundStart.y);
            int startZ = (int) (start.z - pathFindBoundStart.z);
            int endX = (int) (end.x - pathFindBoundStart.x);
            int endY = (int) (end.y - pathFindBoundStart.y);
            int endZ = (int) (end.z - pathFindBoundStart.z);

            // Start async calculation
            isCalculating.set(true);
            CompletableFuture.supplyAsync(() -> {
                return findPath(costs, startX, startY, startZ, endX, endY, endZ);
            }).thenAcceptAsync(newPath -> {
                path = newPath;
                pathOrigin = pathFindBoundStart;
                isCalculating.set(false);

                if (path != null) {
                    List<CubeData> cubes = new ArrayList<>();
                    for (int[] coords : path) {
                        // Convert array indices back to world coordinates
                        float worldX = (float) (pathOrigin.x + coords[0]);
                        float worldY = (float) (pathOrigin.y + coords[1]);
                        float worldZ = (float) (pathOrigin.z + coords[2]);
                        cubes.add(CubeData.create(worldX, worldY, worldZ, 1f, 0f, 0f, 1f, 0.75f));
                    }
                    client.clearCubes();
                    client.renderCubes(cubes);
                }
            }, mc);
        }

        if (path == null || pathOrigin == null || path.size() < 2) return;

        // Check if we should advance to the next path
        Vec3 nextNodePos = new Vec3(pathOrigin.x + path.get(1)[0], pathOrigin.y + path.get(1)[1], pathOrigin.z + path.get(1)[2]);
        if (nextNodePos.distanceTo(player.position()) < 1.5) {
            path.removeFirst();
        }

        if (path.size() < 2) {
            player.displayClientMessage(Component.literal("Reached end of current path..."), true);
            return;
        }
        
        // Re-evaluate next node after potential removal
        nextNodePos = new Vec3(pathOrigin.x + path.get(1)[0], pathOrigin.y + path.get(1)[1], pathOrigin.z + path.get(1)[2]);

        // Navigate autonomously using the path
        BlockPos pos = new BlockPos(
                (int)nextNodePos.x,
                (int)nextNodePos.y,
                (int)nextNodePos.z
        ); 

        // Rotate player
        double dx = pos.getX() + 0.5 - player.position().x;
        double dz = pos.getZ() + 0.5 - player.position().z;
        double degrees = -Math.toDegrees(Math.atan2(dx, dz));
        player.setYRot(wrapYawDegrees(degrees));

        // Reset controls
        if (level.getBlockState(pos.below()).getBlock() instanceof AirBlock) {
            mc.options.keyUp.setDown(false);
            player.displayClientMessage(Component.literal("Waiting... (" + path.size() + " waypoints left)"), true);

            if (player.getRotationVector().y == 90) {
                mc.options.keyUse.setDown(true);
            }
        } else {
            mc.options.keyUp.setDown(true);
            player.displayClientMessage(Component.literal("Navigating... (" + path.size() + " waypoints left)"), true);
        }

        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyDown.setDown(false);

        // Check if jump needed
        if (player.onGround() && player.position().y < pos.getY()) {
            mc.options.keyJump.setDown(true);
            player.setXRot(90);
            mc.options.keyUse.setDown(true);

            player.displayClientMessage(Component.literal("Jumping... (" + path.size() + " waypoints left)"), true);
        } else if (!player.onGround() && Math.abs(player.position().y - pos.getY()) > 1) {
            if (lastSolidBlockBelow != null) {
                aimAtBlockTop(player, lastSolidBlockBelow);
            } else {
                player.setXRot(90);
            }
            mc.options.keyUse.setDown(true);

            player.displayClientMessage(Component.literal("Placing block underneath... (" + path.size() + " waypoints left)"), true);
        } else {
            mc.options.keyUse.setDown(false);

            if (player.getRotationVector().x == 90) {
                player.setXRot(0);
            }

	            // Check if we need to bridge (floating blocks on same Y level)
	            if (player.onGround() && level.getBlockState(pos.below()).getBlock() instanceof AirBlock && level.getBlockState(pos.subtract(new Vec3i(0, 2, 0))).getBlock() instanceof AirBlock) {
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
	                    player.setXRot(80);
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
                    player.setXRot(75);
                }
            } else {
                lastBridgePos = null;
                bridgeNoMoveTicks = 0;
                bridgeFallbackActive = false;
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
}
