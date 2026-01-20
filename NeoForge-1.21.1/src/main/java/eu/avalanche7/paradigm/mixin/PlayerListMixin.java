package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import eu.avalanche7.paradigm.platform.MinecraftPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
    private void paradigm$filterJoinLeaveMessages(Component message, boolean overlay, CallbackInfo ci) {
        if (message == null) return;

        // Only suppress vanilla join/leave messages (translatable multiplayer.player.*)
        int type = paradigm$getVanillaJoinLeaveType(message);
        if (type == 0) return;

        Services services = Paradigm.getServices();
        if (services == null) return;
        ChatConfigHandler.Config cfg = services.getChatConfig();
        if (cfg == null) return;

        boolean suppressJoin = Boolean.TRUE.equals(cfg.enableJoinLeaveMessages.get()) || Boolean.TRUE.equals(cfg.enableFirstJoinMessage.get());
        boolean suppressLeave = Boolean.TRUE.equals(cfg.enableJoinLeaveMessages.get());

        if ((type == 1 && suppressJoin) || (type == 2 && suppressLeave)) {
            ci.cancel();
        }
    }

    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"), cancellable = true)
    private void paradigm$formatChatMessage(PlayerChatMessage playerChatMessage, ServerPlayer sender, net.minecraft.network.chat.ChatType.Bound boundChatType, CallbackInfo ci) {
        Services services = Paradigm.getServices();
        if (services == null) return;

        ChatConfigHandler.Config cfg = services.getChatConfig();
        if (cfg == null || !Boolean.TRUE.equals(cfg.enableCustomChatFormat.get())) {
            return;
        }

        try {
            String messageText = playerChatMessage.decoratedContent().getString();
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
                finalMessage = Component.literal(formattedText);
            }

            PlayerList playerList = (PlayerList) (Object) this;
            for (ServerPlayer player : playerList.getPlayers()) {
                player.sendSystemMessage(finalMessage);
            }

            ci.cancel();
        } catch (Exception e) {
            System.err.println("[Paradigm-NeoForge] Error formatting chat message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private int paradigm$getVanillaJoinLeaveType(Component message) {
        try {
            if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
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
