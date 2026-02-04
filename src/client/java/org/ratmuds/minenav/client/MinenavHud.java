package org.ratmuds.minenav.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

class TextElement {
    String text;
    int color;
    int marginTop;

    TextElement(String text, int color, int marginTop) {
        this.text = text;
        this.color = color;
        this.marginTop = marginTop;
    }
}

final class MinenavHud {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int PADDING = 6;

    private MinenavHud() {
    }

    static void onHudRender(GuiGraphics graphics, DeltaTracker tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        MinenavClient client = MinenavClient.getInstance();
        if (client == null) return;

        List<TextElement> elements = new ArrayList<>();
        elements.add(new TextElement("minenav", WHITE, 0));

        // Status group
        if (client.isNavigating()) {
            elements.add(new TextElement("[ running ]", WHITE, 10));
        } else {
            elements.add(new TextElement("[ ready ]", WHITE, 10));
        }

        // Pathfinding group
        elements.add(new TextElement("[ pathfinding ]", WHITE, 10));

        if (client.isHudCalculatingPath()) {
            elements.add(new TextElement("[ recalculating ]", WHITE, 0));
        }

        if (client.shouldBridge()) {
            elements.add(new TextElement("[ OK ] bridge", WHITE, 0));
        } else {
            elements.add(new TextElement("[ NO ] bridge", WHITE, 0));
        }

        if (client.shouldPillar()) {
            elements.add(new TextElement("[ OK ] pillar", WHITE, 0));
        } else {
            elements.add(new TextElement("[ NO ] pillar", WHITE, 0));
        }

        if (client.shouldJump()) {
            elements.add(new TextElement("[ OK ] jump", WHITE, 0));
        } else {
            elements.add(new TextElement("[ NO ] jump", WHITE, 0));
        }

        if (client.shouldPlaceUnderneath()) {
            elements.add(new TextElement("[ OK ] place underneath", WHITE, 0));
        } else {
            elements.add(new TextElement("[ NO ] place underneath", WHITE, 0));
        }

        // Draw text
        int currentY = 5;
        for (TextElement element : elements) {
            currentY += element.marginTop;
            graphics.drawString(mc.font, element.text, 5, currentY, element.color, true);
            currentY += mc.font.lineHeight;
        }

        return;

        /*boolean navigating = client.isNavigating();
        Vec3 end = client.getEndPos();
        if (!navigating && end == null) return;

        int x = PADDING;
        int y = PADDING;
        int line = mc.font.lineHeight + 2;

        String status = navigating
                ? (client.isHudCalculatingPath() ? "MineNav: recalculating..." : "MineNav: navigating")
                : "MineNav: ready";
        graphics.drawString(mc.font, status, x, y, WHITE, true);
        y += line;

        if (navigating) {
            String action;
            if (client.shouldPlaceUnderneath()) action = "Action: place underneath";
            else if (client.shouldBridge() && client.shouldPillar()) action = "Action: bridge + pillar";
            else if (client.shouldBridge()) action = "Action: bridge";
            else if (client.shouldPillar()) action = "Action: pillar";
            else if (client.shouldJump()) action = "Action: jump";
            else action = "Action: walk";
            graphics.drawString(mc.font, action, x, y, WHITE, true);
            y += line;
        }

        if (end != null) {
            BlockPos endBlock = BlockPos.containing(end);
            graphics.drawString(mc.font, "End: " + endBlock.getX() + " " + endBlock.getY() + " " + endBlock.getZ(), x, y, WHITE, true);
            y += line;

            double dist = mc.player.position().distanceTo(end);
            double distRounded = Math.round(dist * 10.0) / 10.0;
            graphics.drawString(mc.font, "Dist: " + distRounded + "m", x, y, WHITE, true);
            y += line;
        }

        if (navigating) {
            BlockPos next = client.getHudNextTarget();
            if (next != null) {
                graphics.drawString(mc.font, "Next: " + next.getX() + " " + next.getY() + " " + next.getZ(), x, y, WHITE, true);
                y += line;
            }
            int waypointsLeft = client.getHudWaypointsLeft();
            if (waypointsLeft > 0) {
                graphics.drawString(mc.font, "Waypoints: " + waypointsLeft, x, y, WHITE, true);
            }
        }*/
    }
}
