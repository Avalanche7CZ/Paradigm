package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import eu.avalanche7.paradigm.platform.MinecraftPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    static {
        System.out.println("[Paradigm-Mixin] PlayerListMixin loaded!");
    }

    @Inject(method = "*(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void paradigm$filterJoinLeaveMessages(Component message, boolean overlay, CallbackInfo ci) {
        if (message == null) return;

        Services services = Paradigm.getServices();
        if (services == null) return;
        ChatConfigHandler.Config cfg = services.getChatConfig();
        boolean joinLeaveEnabled = cfg != null && Boolean.TRUE.equals(cfg.enableJoinLeaveMessages.get());

        if (joinLeaveEnabled && paradigm$isVanillaJoinLeave(message)) {
            System.out.println("[Paradigm-Mixin] Suppressed vanilla: " + paradigm$safeToString(message));
            ci.cancel();
        }
    }

    @Inject(method = "*(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void paradigm$formatChatMessage(net.minecraft.network.chat.PlayerChatMessage playerChatMessage, ServerPlayer sender, net.minecraft.network.chat.ChatType.Bound boundChatType, CallbackInfo ci) {
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

            formattedText = services.getPlaceholders().replacePlaceholders(formattedText, new MinecraftPlayer(sender));

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
            System.err.println("[Paradigm-Mixin] Error formatting chat message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean paradigm$isVanillaJoinLeave(Component message) {
        try {
            if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
                String key = tc.getKey();
                return "multiplayer.player.joined".equals(key) ||
                       "multiplayer.player.left".equals(key) ||
                       "multiplayer.player.joined.renamed".equals(key);
            }

            String text = message.getString();
            if (text != null) {
                String lower = text.toLowerCase();
                if ((lower.endsWith(" joined the game") || lower.endsWith(" left the game")) &&
                    !lower.contains("&") && !lower.contains("§")) {
                    return true;
                }
            }
        } catch (Throwable t) {
        }
        return false;
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
