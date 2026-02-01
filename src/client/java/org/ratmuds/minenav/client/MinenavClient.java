package org.ratmuds.minenav.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class MinenavClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("minenav");
    private static MinenavClient instance;
    private CubeRenderer cubeRenderer;

    public static MinenavClient getInstance() {
        return instance;
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