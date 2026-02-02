package org.ratmuds.minenav.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class MinenavClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("minenav");
    private static MinenavClient instance;
    private CubeRenderer cubeRenderer;
    private boolean isNavigating = false;
    private Vec3 startPos;
    private Vec3 endPos;

    public static MinenavClient getInstance() {
        return instance;
    }

    public boolean isNavigating() {
        return isNavigating;
    }

    public void setNavigating(boolean navigating) {
        this.isNavigating = navigating;
        if (!navigating) {
            clearCubes();
            // Reset keys when stopping
            if (net.minecraft.client.Minecraft.getInstance().options != null) {
                net.minecraft.client.Minecraft.getInstance().options.keyUp.setDown(false);
                net.minecraft.client.Minecraft.getInstance().options.keyJump.setDown(false);
                net.minecraft.client.Minecraft.getInstance().options.keyUse.setDown(false);

                cubeRenderer.clearCubes();
            }
        }
    }

    public void setStartPos(Vec3 startPos) {
        this.startPos = startPos;
    }

    public Vec3 getStartPos() {
        return startPos;
    }

    public void setEndPos(Vec3 endPos) {
        this.endPos = endPos;
    }

    public Vec3 getEndPos() {
        return endPos;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        cubeRenderer = new CubeRenderer();
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(this::onWorldRender);
    }

    private void onWorldRender(WorldRenderContext context) {
        cubeRenderer.render(context);
    }

    public void clearCubes() {
        cubeRenderer.clearCubes();
    }

    public void renderCubes(List<CubeData> cubes) {
        cubeRenderer.setCubes(cubes);
    }

    public void addCube(CubeData cube) {
        cubeRenderer.addCube(cube);
    }

    public void close() {
        cubeRenderer.close();
    }
}