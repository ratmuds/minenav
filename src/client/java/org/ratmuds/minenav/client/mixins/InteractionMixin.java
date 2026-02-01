package org.ratmuds.minenav.client.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
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

import java.util.Arrays;
import java.util.List;

@Mixin(Player.class)
public class InteractionMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return;
        Player player = mc.player;
        ClientLevel level = mc.level;

        // Get the client instance
        MinenavClient client = MinenavClient.getInstance();

        // Path find from (0, -60, 0) to (10, -60, 10)
        Vec3 start = Vec3.ZERO;
        Vec3 end = new Vec3(10, -60, 10);
        Vec3 pathFindBoundStart = new Vec3(-10, -60, -10);
        Vec3 pathFindBoundEnd = new Vec3(20, -60, 20);

        // Setup costs
        /*double[][][] costs = new double[(int) Math.abs(pathFindBoundEnd.x - pathFindBoundStart.x)][(int) Math.abs(pathFindBoundEnd.y - pathFindBoundStart.y)][(int) Math.abs(pathFindBoundEnd.z - pathFindBoundStart.z)];

        // Loop through blocks
        for (int x = 0; x < end.x - start.x; x++) {
            for (int y = 0; y < end.y - start.y; y++) {
                for (int z = 0; z < end.z - start.z; z++) {
                    // Fetch block in that position
                    Block block = level.getBlockState(new BlockPos((int) (start.x + x), (int) (start.y + y), (int) (start.z + z))).getBlock();

                    if (block.)

                    costs[x][y][z] = 1.0;
                }
            }
        }*/

        // Remove all old cubes
        client.clearCubes();
        // Render a list of new cubes
        List<CubeData> cubes = Arrays.asList(
                CubeData.create(0f, -60f, 0f, 100f, 0f, 1f, 0f, 0.5f),  // x, y, z, size, r, g, b, alpha
                CubeData.create(10f, -60f, 10f, 1f, 1f, 0f, 0f, 0.5f)
        );
        client.renderCubes(cubes);

        // Example: Draw a line from player's feet to 10 blocks above
        //Vec3 start = Vec3.ZERO;
        //Vec3 end = player.position().add(0, 300, 0);
        //LineRenderer.drawLine(start, end, 0xFF00FF00); // Green line (0xAARRGGBB format)

        //mc.options.keyUp.setDown(true);
    }
}
