package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PlayerManager.class)
public abstract class PlayerListMixin {

    @Shadow
    public abstract List<ServerPlayerEntity> getPlayerList();

    @Inject(method = "broadcast(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"), cancellable = true)
    private void paradigm$filterJoinLeaveMessages(Text message, boolean overlay, CallbackInfo ci) {
        if (message == null) return;

        int type = paradigm$getVanillaJoinLeaveType(message);
        if (type == 0) return;

        Services services = ParadigmAPI.getServices();
        if (services == null) return;

        ChatConfigHandler.Config cfg = services.getChatConfig();
        boolean joinLeaveEnabled = cfg != null && cfg.enableJoinLeaveMessages.value;

        if (joinLeaveEnabled) {
            ci.cancel();
        }
    }

    @Inject(method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V",
            at = @At("HEAD"), cancellable = true)
    private void paradigm$formatChatMessage(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params, CallbackInfo ci) {
        Services services = ParadigmAPI.getServices();
        if (services == null) return;

        ChatConfigHandler.Config cfg = services.getChatConfig();
        if (cfg == null || !cfg.enableCustomChatFormat.value) {
            return;
        }

        try {
            String messageText = message.getContent().getString();
            String customFormat = cfg.customChatFormat.value;

            if (customFormat == null || customFormat.isEmpty()) {
                return;
            }

            String formattedText = customFormat.replace("{message}", messageText);

            IPlayer wrappedSender = services.getPlatformAdapter().wrapPlayer(sender);
            formattedText = services.getPlaceholders().replacePlaceholders(formattedText, sender);

            IComponent parsedComponent = services.getMessageParser().parseMessage(formattedText, wrappedSender);

            Text finalMessage = parsedComponent.getOriginalText();

            for (ServerPlayerEntity player : getPlayerList()) {
                player.sendMessage(finalMessage, false);
            }

            ci.cancel();
        } catch (Exception e) {
            services.getDebugLogger().debugLog("PlayerListMixin: Error formatting chat message - " + e.getMessage(), e);
        }
    }

    @Unique
    private int paradigm$getVanillaJoinLeaveType(Text message) {
        try {
            if (message.getContent() instanceof TranslatableTextContent tc) {
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

    @Unique
    private String paradigm$safeToString(Text c) {
        try {
            String text = c == null ? "null" : c.getString();
            return text.length() > 50 ? text.substring(0, 50) + "..." : text;
        } catch (Throwable t) {
            return "<component>";
        }
    }
}
