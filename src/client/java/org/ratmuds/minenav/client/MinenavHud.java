package org.ratmuds.minenav.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

final class MinenavHud {
    private static final int PANEL_BG = 0x77000000;
    private static final int PANEL_BORDER = 0xAA111111;

    private static final int TXT_TITLE = 0xFFFFFFFF;
    private static final int TXT_LABEL = 0xFFAAAAAA;
    private static final int TXT_VALUE = 0xFFEFEFEF;
    private static final int TXT_MUTED = 0xFF888888;

    private static final int TAG_OK_BG = 0xFF1E7A1E;
    private static final int TAG_NO_BG = 0xFF8A1F1F;
    private static final int TAG_WARN_BG = 0xFF8A6B00;
    private static final int TAG_INFO_BG = 0xFF1F5F8A;
    private static final int TAG_TXT = 0xFFFFFFFF;

    private static final int PADDING = 6;
    private static final int TAG_PAD_X = 3;

    private MinenavHud() {
    }

    private static final class Segment {
        final String text;
        final int color;
        final Integer bgColor; // when non-null, draw a pill background

        Segment(String text, int color) {
            this(text, color, null);
        }

        Segment(String text, int color, Integer bgColor) {
            this.text = text;
            this.color = color;
            this.bgColor = bgColor;
        }
    }

    private static final class Line {
        final List<Segment> segments = new ArrayList<>();
        final int marginTop;
        final boolean separator;

        private Line(int marginTop, boolean separator) {
            this.marginTop = marginTop;
            this.separator = separator;
        }

        static Line separator(int marginTop) {
            return new Line(marginTop, true);
        }

        static Line text(int marginTop, Segment... segments) {
            Line line = new Line(marginTop, false);
            line.segments.addAll(List.of(segments));
            return line;
        }
    }

    private static int lineWidth(Minecraft mc, Line line) {
        int width = 0;
        for (Segment seg : line.segments) {
            int segW = mc.font.width(seg.text);
            if (seg.bgColor != null) {
                segW += TAG_PAD_X * 2;
            }
            width += segW;
        }
        return width;
    }

    static void onHudRender(GuiGraphics graphics, DeltaTracker tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        MinenavClient client = MinenavClient.getInstance();
        if (client == null) return;

        List<Line> lines = new ArrayList<>();
        lines.add(Line.text(0,
                new Segment("minenav", TXT_TITLE),
                new Segment("  ", TXT_TITLE),
                client.isNavigating()
                        ? new Segment("[RUNNING]", TAG_TXT, TAG_INFO_BG)
                        : new Segment("[READY]", TAG_TXT, TAG_OK_BG)
        ));

        lines.add(Line.separator(6));

        String hand = mc.player.getMainHandItem().isEmpty()
                ? "empty"
                : mc.player.getMainHandItem().getHoverName().getString();
        int slot = client.getHudSelectedHotbarSlot();
        lines.add(Line.text(6,
                new Segment("[hand]", TXT_LABEL),
                new Segment(" ", TXT_MUTED),
                new Segment((slot >= 0 ? (slot + 1) + ": " : "") + hand, TXT_VALUE)
        ));

        String action = client.getHudAction();
        lines.add(Line.text(0,
                new Segment("[action]", TXT_LABEL),
                new Segment(" ", TXT_MUTED),
                new Segment(action, TXT_VALUE)
        ));

        BlockPos actionTarget = client.getHudActionTarget();
        if (actionTarget != null && mc.level != null) {
            BlockState st = mc.level.getBlockState(actionTarget);
            String blockName = st.getBlock().getDescriptionId();
            ItemStack asItem = new ItemStack(st.getBlock().asItem());
            if (!asItem.isEmpty()) {
                blockName = asItem.getHoverName().getString();
            }
            lines.add(Line.text(0,
                    new Segment("[target]", TXT_LABEL),
                    new Segment(" ", TXT_MUTED),
                    new Segment(actionTarget.getX() + " " + actionTarget.getY() + " " + actionTarget.getZ(), TXT_VALUE),
                    new Segment("  ", TXT_MUTED),
                    new Segment(blockName, TXT_MUTED)
            ));
        }

        Vec3 end = client.getEndPos();
        if (end != null) {
            BlockPos endBlock = BlockPos.containing(end);
            lines.add(Line.text(6,
                    new Segment("[end]", TXT_LABEL),
                    new Segment(" ", TXT_MUTED),
                    new Segment(endBlock.getX() + " " + endBlock.getY() + " " + endBlock.getZ(), TXT_VALUE)
            ));
            double dist = mc.player.position().distanceTo(end);
            double distRounded = Math.round(dist * 10.0) / 10.0;
            lines.add(Line.text(0,
                    new Segment("[dist]", TXT_LABEL),
                    new Segment(" ", TXT_MUTED),
                    new Segment(distRounded + "m", TXT_VALUE)
            ));
        }

        // Pathfinding group
        lines.add(Line.separator(8));
        lines.add(Line.text(4, new Segment("pathfinding", TXT_TITLE)));

        if (client.isHudCalculatingPath()) {
            lines.add(Line.text(4, new Segment("CALC", TAG_TXT, TAG_WARN_BG), new Segment(" recalculating", TXT_VALUE)));
        }

        BlockPos next = client.getHudNextTarget();
        if (next != null) {
            lines.add(Line.text(0,
                    new Segment("[next]", TXT_LABEL),
                    new Segment(" ", TXT_MUTED),
                    new Segment(next.getX() + " " + next.getY() + " " + next.getZ(), TXT_VALUE)
            ));
        }

        int waypoints = client.getHudWaypointsLeft();
        if (waypoints > 0) {
            lines.add(Line.text(0,
                    new Segment("[waypoints]", TXT_LABEL),
                    new Segment(" ", TXT_MUTED),
                    new Segment(String.valueOf(waypoints), TXT_VALUE)
            ));
        }

        int failedRecalcs = client.getHudFailedRecalcCount();
        int recalcCooldownTicks = client.getHudRecalcCooldownTicks();
        if (failedRecalcs > 0) {
            String cooldown = "";
            if (recalcCooldownTicks > 0) {
                double secs = recalcCooldownTicks / 20.0;
                double secsRounded = Math.round(secs * 10.0) / 10.0;
                cooldown = "  (" + secsRounded + "s)";
            }
            lines.add(Line.text(4,
                    new Segment("[WARN]", TAG_TXT, TAG_WARN_BG),
                    new Segment(" failed recalcs: ", TXT_VALUE),
                    new Segment(String.valueOf(failedRecalcs), TXT_VALUE),
                    new Segment(cooldown, TXT_MUTED)
            ));
        }

        lines.add(Line.text(4,
                client.shouldBridge()
                        ? new Segment("OK", TAG_TXT, TAG_OK_BG)
                        : new Segment("NO", TAG_TXT, TAG_NO_BG),
                new Segment(" bridge", TXT_VALUE)
        ));

        lines.add(Line.text(0,
                client.shouldPillar()
                        ? new Segment("OK", TAG_TXT, TAG_OK_BG)
                        : new Segment("NO", TAG_TXT, TAG_NO_BG),
                new Segment(" pillar", TXT_VALUE)
        ));

        lines.add(Line.text(0,
                client.shouldJump()
                        ? new Segment("OK", TAG_TXT, TAG_OK_BG)
                        : new Segment("NO", TAG_TXT, TAG_NO_BG),
                new Segment(" jump", TXT_VALUE)
        ));

        lines.add(Line.text(0,
                client.shouldPlaceUnderneath()
                        ? new Segment("OK", TAG_TXT, TAG_OK_BG)
                        : new Segment("NO", TAG_TXT, TAG_NO_BG),
                new Segment(" place underneath", TXT_VALUE)
        ));

        int x0 = 5;
        int y0 = 5;

        int totalHeight = 0;
        int maxWidth = 0;
        for (Line line : lines) {
            totalHeight += line.marginTop;
            if (!line.separator) {
                maxWidth = Math.max(maxWidth, lineWidth(mc, line));
                totalHeight += mc.font.lineHeight;
            } else {
                totalHeight += 2;
            }
        }

        int panelW = (PADDING * 2) + maxWidth;
        int panelH = (PADDING * 2) + totalHeight;
        graphics.fill(x0 - 1, y0 - 1, x0 + panelW + 1, y0 + panelH + 1, PANEL_BORDER);
        graphics.fill(x0, y0, x0 + panelW, y0 + panelH, PANEL_BG);

        int x = x0 + PADDING;
        int y = y0 + PADDING;
        for (Line line : lines) {
            y += line.marginTop;

            if (line.separator) {
                graphics.fill(x0 + PADDING, y, x0 + panelW - PADDING, y + 1, 0x44FFFFFF);
                y += 2;
                continue;
            }

            int cx = x;
            for (Segment seg : line.segments) {
                if (seg.bgColor != null) {
                    int w = mc.font.width(seg.text) + TAG_PAD_X * 2;
                    int h = mc.font.lineHeight;
                    graphics.fill(cx, y - 1, cx + w, y + h, seg.bgColor);
                    graphics.drawString(mc.font, seg.text, cx + TAG_PAD_X, y, seg.color, true);
                    cx += w;
                } else {
                    graphics.drawString(mc.font, seg.text, cx, y, seg.color, true);
                    cx += mc.font.width(seg.text);
                }
            }
            y += mc.font.lineHeight;
        }
    }
}
