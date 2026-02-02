package org.ratmuds.minenav.client.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.ratmuds.minenav.client.MinenavClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.screens.ChatScreen;

@Mixin(ChatScreen.class)
public class ChatMixin {
    @Inject(at = @At("HEAD"), method = "handleChatInput")
    private void onChat(String message, boolean bl, CallbackInfo ci) {
        if (message.startsWith("#")) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) return;
            Player player = mc.player;

            String[] parts = message.substring(1).split(" ");
            String command = parts[0];
            
            if (command.equalsIgnoreCase("start")) {
                MinenavClient.getInstance().setStartPos(player.position());
                player.displayClientMessage(Component.literal(String.format("Start set to (%.1f, %.1f, %.1f)", player.position().x, player.position().y, player.position().z)), true);
            } else if (command.equalsIgnoreCase("end")) {
                MinenavClient.getInstance().setEndPos(player.position());
                player.displayClientMessage(Component.literal(String.format("End set to (%.1f, %.1f, %.1f)", player.position().x, player.position().y, player.position().z)), true);
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
        }
    }
}
