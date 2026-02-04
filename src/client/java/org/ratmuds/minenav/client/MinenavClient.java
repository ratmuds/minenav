package org.ratmuds.minenav.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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

    private boolean hudCalculatingPath = false;
    private int hudWaypointsLeft = 0;
    private BlockPos hudNextTarget = null;

    private boolean shouldBridge = false;
    private boolean shouldPillar = false;
    private boolean shouldJump = false;
    private boolean shouldPlaceUnderneath = false;

    public static MinenavClient getInstance() {
        return instance;
    }

    public boolean isNavigating() {
        return isNavigating;
    }

    public void setNavigating(boolean navigating) {
        this.isNavigating = navigating;
        if (!navigating) {
            clearHudNavigationState();
            updatePathfindingState(false, false, false, false);
            //clearCubes();
            // Reset keys when stopping
            Minecraft.getInstance().options.keyUp.setDown(false);
            Minecraft.getInstance().options.keyDown.setDown(false);
            Minecraft.getInstance().options.keyJump.setDown(false);
            Minecraft.getInstance().options.keyUse.setDown(false);
            Minecraft.getInstance().options.keyShift.setDown(false);
            Minecraft.getInstance().options.keyAttack.setDown(false);

            //cubeRenderer.clearCubes();
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

    public boolean isHudCalculatingPath() {
        return hudCalculatingPath;
    }

    public int getHudWaypointsLeft() {
        return hudWaypointsLeft;
    }

    public BlockPos getHudNextTarget() {
        return hudNextTarget;
    }

    public void updateHudNavigationState(boolean calculatingPath, int waypointsLeft, BlockPos nextTarget) {
        this.hudCalculatingPath = calculatingPath;
        this.hudWaypointsLeft = waypointsLeft;
        this.hudNextTarget = nextTarget;
    }

    public void updatePathfindingState(boolean shouldBridge, boolean shouldPillar, boolean shouldJump, boolean shouldPlaceUnderneath) {
        this.shouldBridge = shouldBridge;
        this.shouldPillar = shouldPillar;
        this.shouldJump = shouldJump;
        this.shouldPlaceUnderneath = shouldPlaceUnderneath;
    }

    public boolean shouldBridge() {
        return shouldBridge;
    }

    public boolean shouldPillar() {
        return shouldPillar;
    }

    public boolean shouldJump() {
        return shouldJump;
    }

    public boolean shouldPlaceUnderneath() {
        return shouldPlaceUnderneath;
    }

    public void clearHudNavigationState() {
        updateHudNavigationState(false, 0, null);
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        cubeRenderer = new CubeRenderer();
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(this::onWorldRender);

        HudRenderCallback.EVENT.register(MinenavHud::onHudRender);
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
