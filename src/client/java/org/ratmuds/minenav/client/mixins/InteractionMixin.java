package org.ratmuds.minenav.client.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
import java.util.Arrays;
import java.util.List;

import static org.ratmuds.minenav.client.AStar3D.findPath;

@Mixin(Player.class)
public class InteractionMixin {
    private static int tickCounter = 0;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        tickCounter++;
        if (tickCounter % 20 != 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return;
        Player player = mc.player;
        ClientLevel level = mc.level;

        // Get the client instance
        MinenavClient client = MinenavClient.getInstance();

        // Path find from (0, -50, 0) to (10, -50, 10)
        Vec3 start = new Vec3(0, -60, 0);
        Vec3 end = new Vec3(10, -60, 10);
        // Bounds must include start/end Y and account for 2-block height check (y+1)
        // Need at least 3 Y levels: feet level, head level (y+1), and one below for ground
        Vec3 pathFindBoundStart = new Vec3(-10, -61, -10);
        Vec3 pathFindBoundEnd = new Vec3(21, -55, 21);  // Extended Y range for head clearance check

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

        List<CubeData> cubes = new ArrayList<>();

        // Loop through all positions in the search bounds
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
                        costs[x][y][z] = 1.0;  // Use small positive cost, not 0!
                    } else {
                        costs[x][y][z] = Double.POSITIVE_INFINITY;  // Use infinity for unwalkable
                        //cubes.add(CubeData.create(worldX, worldY, worldZ, 1f, 1f, 0f, 0f, 0.75f));
                    }

                    // Log position, block type, and cost
                    //MinenavClient.LOGGER.info(String.format("[MineNav] (%d, %d, %d) - %s - %.2f", worldX, worldY, worldZ, block.getClass().getSimpleName(), costs[x][y][z]));
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

        // Log pathfinding bounds
        MinenavClient.LOGGER.info(String.format("[MineNav] Pathfinding bounds: Start (%d, %d, %d), End (%d, %d, %d)",
                startX, startY, startZ,
                endX, endY, endZ));
        MinenavClient.LOGGER.info(String.format("[MineNav] Array dimensions: (%d, %d, %d)", sizeX, sizeY, sizeZ));

        // Path find
        List<int[]> path = findPath(costs, startX, startY, startZ, endX, endY, endZ);
        
        // Remove all old cubes and render new ones (do this regardless of path result)
        client.clearCubes();
        client.renderCubes(cubes);
        
        if (path == null) {
            MinenavClient.LOGGER.info("[MineNav] No path found");
            return;
        }

        MinenavClient.LOGGER.info(String.format("[MineNav] Path generated with %d elements", path.size()));
        //List<CubeData> cubes = new ArrayList<>();
        for (int[] coords : path) {
            // Convert array indices back to world coordinates
            float worldX = (float) (pathFindBoundStart.x + coords[0]);
            float worldY = (float) (pathFindBoundStart.y + coords[1]);
            float worldZ = (float) (pathFindBoundStart.z + coords[2]);
            cubes.add(CubeData.create(worldX, worldY, worldZ, 1f, 0f, 0f, 1f, 0.75f));
        }

        client.clearCubes();
        client.renderCubes(cubes);

        // Example: Draw a line from player's feet to 10 blocks above
        //Vec3 start = Vec3.ZERO;
        //Vec3 end = player.position().add(0, 300, 0);
        //LineRenderer.drawLine(start, end, 0xFF00FF00); // Green line (0xAARRGGBB format)

        //mc.options.keyUp.setDown(true);
    }
}