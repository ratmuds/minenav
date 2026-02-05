package org.ratmuds.minenav.client.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.ratmuds.minenav.client.BlockMatcher;
import org.ratmuds.minenav.client.MinenavClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.screens.ChatScreen;

@Mixin(ChatScreen.class)
public class ChatMixin {
    @Inject(at = @At("HEAD"), method = "handleChatInput", cancellable = true)
    private void onChat(String message, boolean bl, CallbackInfo ci) {
        if (message.startsWith("#")) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) return;
            Player player = mc.player;
            ClientLevel level = mc.level;

            String raw = message.substring(1).trim();
            if (raw.isEmpty()) {
                ci.cancel();
                return;
            }

            String[] parts = raw.split("\\s+");
            String command = parts[0];
            
            if (command.equalsIgnoreCase("start")) {
                MinenavClient.getInstance().setStartPos(player.position());
                player.displayClientMessage(Component.literal(String.format("Start set to (%.1f, %.1f, %.1f)", player.position().x, player.position().y, player.position().z)), true);
            } else if (command.equalsIgnoreCase("end")) {
                MinenavClient.getInstance().setEndPos(player.position());
                player.displayClientMessage(Component.literal(String.format("End set to (%.1f, %.1f, %.1f)", player.position().x, player.position().y, player.position().z)), true);
            } else if (command.equalsIgnoreCase("algo") || command.equalsIgnoreCase("algorithm") || command.equalsIgnoreCase("pathfinder")) {
                if (parts.length < 2) {
                    var current = MinenavClient.getInstance().getPathfinderAlgorithm();
                    player.displayClientMessage(Component.literal("Pathfinder: " + current.label() + " (use: #algo astar|adstar)"), true);
                    ci.cancel();
                    return;
                }

                String a = parts[1].trim().toLowerCase();
                if (a.equals("astar") || a.equals("a*") || a.equals("a")) {
                    MinenavClient.getInstance().setPathfinderAlgorithm(MinenavClient.PathfinderAlgorithm.ASTAR);
                } else if (a.equals("adstar") || a.equals("anytime") || a.equals("dstar") || a.equals("d*") || a.equals("anytime_dstar")) {
                    MinenavClient.getInstance().setPathfinderAlgorithm(MinenavClient.PathfinderAlgorithm.ANYTIME_DSTAR);
                } else if (a.equals("toggle") || a.equals("cycle")) {
                    var current = MinenavClient.getInstance().getPathfinderAlgorithm();
                    MinenavClient.getInstance().setPathfinderAlgorithm(
                            current == MinenavClient.PathfinderAlgorithm.ASTAR
                                    ? MinenavClient.PathfinderAlgorithm.ANYTIME_DSTAR
                                    : MinenavClient.PathfinderAlgorithm.ASTAR
                    );
                } else {
                    player.displayClientMessage(Component.literal("Unknown algo: " + parts[1] + " (use: #algo astar|adstar|toggle)"), true);
                    ci.cancel();
                    return;
                }

                var now = MinenavClient.getInstance().getPathfinderAlgorithm();
                player.displayClientMessage(Component.literal("Pathfinder set to " + now.label()), true);
            } else if (command.equalsIgnoreCase("mine")) {
                String query = raw.substring(command.length()).trim();
                if (query.isEmpty()) {
                    player.displayClientMessage(Component.literal("Usage: #mine <block> (e.g. #mine dirt)"), true);
                    ci.cancel();
                    return;
                }

                var match = BlockMatcher.findClosestBlock(query);
                if (match.isEmpty()) {
                    player.displayClientMessage(Component.literal("No matching block for: " + query), true);
                    ci.cancel();
                    return;
                }

                Block targetBlock = match.get();
                BlockPos origin = player.blockPosition();

                BlockPos target = findNearestBlock(level, origin, targetBlock, 48, 24);
                if (target == null) {
                    player.displayClientMessage(Component.literal("No " + query + " found nearby (search radius ~48)"), true);
                    ci.cancel();
                    return;
                }

                Vec3 end = new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
                MinenavClient.getInstance().setStartPos(player.position());
                MinenavClient.getInstance().setEndPos(end);
                MinenavClient.getInstance().setNavigating(true);

                player.displayClientMessage(Component.literal("Mining target set: " + query + " @ " + target.getX() + " " + target.getY() + " " + target.getZ()), true);
            } else if (command.equalsIgnoreCase("go")) {
                MinenavClient.getInstance().setStartPos(player.position());
                MinenavClient.getInstance().setNavigating(true);
                player.displayClientMessage(Component.literal("Started pathfinding"), true);
            } else if (command.equalsIgnoreCase("stop")) {
                MinenavClient.getInstance().setNavigating(false);

                player.displayClientMessage(Component.literal("Stopped pathfinding"), true);
            } else {
                player.displayClientMessage(Component.literal("Unknown command"), true);
            }

            ci.cancel();
        }
    }

    private static BlockPos findNearestBlock(ClientLevel level, BlockPos origin, Block block, int horizontalRadius, int verticalRadius) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();

        BlockPos best = null;
        long bestDist2 = Long.MAX_VALUE;

        int minX = ox - horizontalRadius;
        int maxX = ox + horizontalRadius;
        int minY = Math.max(level.getMinY(), oy - verticalRadius);
        int maxY = Math.min(level.getMaxY() - 1, oy + verticalRadius);
        int minZ = oz - horizontalRadius;
        int maxZ = oz + horizontalRadius;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutable.set(x, y, z);
                    if (level.getBlockState(mutable).getBlock() != block) continue;

                    long dx = (long) x - (long) ox;
                    long dy = (long) y - (long) oy;
                    long dz = (long) z - (long) oz;
                    long dist2 = dx * dx + dy * dy + dz * dz;
                    if (dist2 < bestDist2) {
                        bestDist2 = dist2;
                        best = mutable.immutable();
                    }
                }
            }
        }

        return best;
    }

    private static BlockPos findStandPositionAdjacent(ClientLevel level, BlockPos origin, BlockPos target) {
        BlockPos best = null;
        long bestDist2 = Long.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            BlockPos pos = target.relative(dir);
            if (!isStandable(level, pos)) continue;

            long dx = (long) pos.getX() - (long) origin.getX();
            long dy = (long) pos.getY() - (long) origin.getY();
            long dz = (long) pos.getZ() - (long) origin.getZ();
            long dist2 = dx * dx + dy * dy + dz * dz;
            if (dist2 < bestDist2) {
                bestDist2 = dist2;
                best = pos;
            }
        }

        return best;
    }

    private static boolean isStandable(ClientLevel level, BlockPos pos) {
        BlockState feetState = level.getBlockState(pos);
        if (!(feetState.getBlock() instanceof AirBlock)) return false;

        BlockState headState = level.getBlockState(pos.above());
        if (!(headState.getBlock() instanceof AirBlock)) return false;

        BlockState support = level.getBlockState(pos.below());
        return !(support.getBlock() instanceof AirBlock);
    }
}
