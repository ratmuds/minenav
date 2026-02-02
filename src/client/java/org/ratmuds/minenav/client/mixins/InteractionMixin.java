package org.ratmuds.minenav.client.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
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

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        tickCounter++;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return;
        Player player = mc.player;
        ClientLevel level = mc.level;

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
        if (start.distanceTo(end) < 3) {
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
                            costs[x][y][z] = 10.0;  // Use small positive cost, not 0

                            // Check if block is floating
                            if (worldY > player.position().y && level.getBlockState(new BlockPos(worldX, worldY - 1, worldZ)).getBlock() instanceof AirBlock) {
                                costs[x][y][z] = 30.0;

                                // Check if block if extra floating
                                if (level.getBlockState(new BlockPos(worldX, worldY - 2, worldZ)).getBlock() instanceof AirBlock) {
                                    costs[x][y][z] = 70.0;
                                }
                            } else if (worldY < player.position().y) {
                                costs[x][y][z] = 20.0;
                            }
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
        player.setYRot((float) degrees);

        // Use controls

        // Check if in front
        if (degrees < 10 || degrees > 350) {
            mc.options.keyUp.setDown(true);
        }

        // Check if jump needed
        if (player.onGround() && player.position().y < pos.getY()) {
            mc.options.keyJump.setDown(true);
            mc.options.keyUse.setDown(false);
        } else if (!player.onGround() && Math.abs(player.position().y - pos.getY()) > 1) {
            player.setXRot(90);
            mc.options.keyUse.setDown(true);
        } else {
            mc.options.keyUse.setDown(false);

            if (player.getRotationVector().x == 90) {
                player.setXRot(0);
            }
        }
        
        mc.options.keyUp.setDown(true);
    }
}
