/*
 * NOTE FOR PORTING (1.19.2 → 1.18.2):
 * - TranslatableComponent is in net.minecraft.network.chat, not .contents.
 * - Use instanceof TranslatableComponent for vanilla join/leave detection.
 * - Do NOT use net.minecraft.network.chat.contents.TranslatableContents (does not exist in 1.18.2).
 */

package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import eu.avalanche7.paradigm.platform.MinecraftPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ChatType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.network.chat.TranslatableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    static {
        System.out.println("[Paradigm-Mixin] PlayerListMixin loaded!");
    }

    @Inject(method = "*(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/ChatType;Ljava/util/UUID;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void paradigm$filterJoinLeaveMessages(Component message, ChatType chatType, UUID uuid, CallbackInfo ci) {
        if (message == null) return;

        int type = paradigm$getVanillaJoinLeaveType(message);
        if (type == 0) return;

        Services services = Paradigm.getServices();
        if (services == null) return;
        ChatConfigHandler.Config cfg = services.getChatConfig();
        boolean joinLeaveEnabled = cfg != null && Boolean.TRUE.equals(cfg.enableJoinLeaveMessages.get());

        if (joinLeaveEnabled) {
            System.out.println("[Paradigm-Mixin] Suppressed vanilla: " + paradigm$safeToString(message));
            ci.cancel();
        }
    }

    @Inject(method = "*(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Lnet/minecraft/network/chat/ChatType;Ljava/util/UUID;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void paradigm$formatChatMessage(Component message, java.util.function.Function<ServerPlayer, Component> function, ChatType chatType, UUID uuid, CallbackInfo ci) {
        Services services = Paradigm.getServices();
        if (services == null) return;

        ChatConfigHandler.Config cfg = services.getChatConfig();
        if (cfg == null || !Boolean.TRUE.equals(cfg.enableCustomChatFormat.get())) {
            return;
        }

        if (chatType != ChatType.CHAT) {
            return;
        }

        try {
            PlayerList playerList = (PlayerList) (Object) this;

            ServerPlayer sender = null;
            for (ServerPlayer player : playerList.getPlayers()) {
                if (player.getUUID().equals(uuid)) {
                    sender = player;
                    break;
                }
            }

            if (sender == null) {
                return;
            }

            Component senderMessage = function.apply(sender);
            String messageText = paradigm$extractMessageText(senderMessage.getString(), sender.getName().getString());

            String customFormat = cfg.customChatFormat.get();
            if (customFormat == null || customFormat.isEmpty()) {
                return;
            }

            String formattedText = customFormat.replace("{message}", messageText);
            IComponent parsedComponent = services.getMessageParser().parseMessage(formattedText, new MinecraftPlayer(sender));

            Component finalMessage;
            if (parsedComponent instanceof MinecraftComponent mc) {
                finalMessage = mc.getHandle();
            } else {
                finalMessage = new net.minecraft.network.chat.TextComponent(formattedText);
            }

            for (ServerPlayer player : playerList.getPlayers()) {
                player.sendMessage(finalMessage, uuid);
            }

            ci.cancel();
        } catch (Exception e) {
            System.err.println("[Paradigm-Mixin] Error formatting chat message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private String paradigm$extractMessageText(String fullMessage, String playerName) {
        if (fullMessage.contains(">")) {
            int idx = fullMessage.lastIndexOf('>');
            if (idx >= 0 && idx < fullMessage.length() - 1) {
                return fullMessage.substring(idx + 1).trim();
            }
        } else if (fullMessage.contains(playerName)) {
            int idx = fullMessage.indexOf(playerName);
            if (idx >= 0) {
                String remaining = fullMessage.substring(idx + playerName.length()).trim();
                if (remaining.startsWith(":") || remaining.startsWith(">")) {
                    remaining = remaining.substring(1).trim();
                }
                return remaining;
            }
        }
        return fullMessage;
    }

    @org.spongepowered.asm.mixin.Unique
    private int paradigm$getVanillaJoinLeaveType(Component message) {
        try {
            if (message instanceof TranslatableComponent tc) {
                String key = tc.getKey();
                if ("multiplayer.player.joined".equals(key) || "multiplayer.player.joined.renamed".equals(key)) return 1;
                if ("multiplayer.player.left".equals(key)) return 2;
            }
            String text = message.getString();
            if (text != null) {
                String lower = text.toLowerCase();
                if (!lower.contains("&") && !lower.contains("§")) {
                    if (lower.endsWith(" joined the game")) return 1;
                    if (lower.endsWith(" left the game")) return 2;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    @org.spongepowered.asm.mixin.Unique
    private String paradigm$safeToString(Component c) {
        try {
            String text = c == null ? "null" : c.getString();
            return text.length() > 50 ? text.substring(0, 50) + "..." : text;
        } catch (Throwable t) {
            return "<component>";
        }
    }
}