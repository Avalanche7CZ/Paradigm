package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    @Unique
    private static final ThreadLocal<Boolean> SUPPRESS_JOIN_BROADCAST = new ThreadLocal<>();

    @Inject(method = "onPlayerConnect", at = @At("HEAD"))
    private void paradigm$decideJoinSuppression(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
        Services services = Paradigm.getServices();
        if (services == null) {
            SUPPRESS_JOIN_BROADCAST.set(Boolean.FALSE);
            return;
        }
        ChatConfigHandler.Config chatConfig = services.getChatConfig();
        boolean enableRegular = chatConfig.enableJoinLeaveMessages.value;
        boolean enableFirst = chatConfig.enableFirstJoinMessage.value;
        boolean isFirstJoin = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.LEAVE_GAME)) == 0;
        boolean shouldSuppress = enableRegular || (enableFirst && isFirstJoin);
        SUPPRESS_JOIN_BROADCAST.set(shouldSuppress);
    }

    @Inject(method = "onPlayerConnect", at = @At("RETURN"))
    private void paradigm$clearJoinSuppression(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
        SUPPRESS_JOIN_BROADCAST.remove();
    }

    @Inject(method = "broadcast(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"), cancellable = true)
    private void paradigm$maybeSuppressBroadcast(Text message, boolean overlay, CallbackInfo ci) {
        if (message == null || !(message.getContent() instanceof TranslatableTextContent)) {
            return;
        }
        TranslatableTextContent content = (TranslatableTextContent) message.getContent();
        String key = content.getKey();
        if ("multiplayer.player.joined".equals(key)) {
            Boolean suppress = SUPPRESS_JOIN_BROADCAST.get();
            if (Boolean.TRUE.equals(suppress)) {
                ci.cancel();
            }
            return;
        }
        if ("multiplayer.player.left".equals(key)) {
            Services services = Paradigm.getServices();
            if (services == null) return;
            ChatConfigHandler.Config chatConfig = services.getChatConfig();
            if (chatConfig != null && chatConfig.enableJoinLeaveMessages.value) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    private void sendCustomJoinMessage(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
        Services services = Paradigm.getServices();
        if (services == null) return;

        ChatConfigHandler.Config chatConfig = services.getChatConfig();
        if (!chatConfig.enableJoinLeaveMessages.value && !chatConfig.enableFirstJoinMessage.value) {
            return;
        }

        IPlatformAdapter platform = services.getPlatformAdapter();
        if (platform == null) return;

        boolean isFirstJoin = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.LEAVE_GAME)) == 0;
        String messageFormat = null;

        if (isFirstJoin && chatConfig.enableFirstJoinMessage.value) {
            messageFormat = chatConfig.firstJoinMessageFormat.value;
        } else if (chatConfig.enableJoinLeaveMessages.value) {
            messageFormat = chatConfig.joinMessageFormat.value;
        }

        if (messageFormat != null && !messageFormat.isEmpty()) {
            IPlayer iPlayer = platform.wrapPlayer(player);
            Text formattedMessage = services.getMessageParser().parseMessage(messageFormat, iPlayer).getOriginalText();
            platform.broadcastSystemMessage(formattedMessage);
        }
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void onPlayerRemoveMixin(ServerPlayerEntity player, CallbackInfo ci) {
        Services services = Paradigm.getServices();
        if (services == null) return;

        ChatConfigHandler.Config chatConfig = services.getChatConfig();
        if (!chatConfig.enableJoinLeaveMessages.value) {
            return;
        }

        IPlatformAdapter platform = services.getPlatformAdapter();
        if (platform == null) return;

        String leaveMessageFormat = chatConfig.leaveMessageFormat.value;

        if (leaveMessageFormat != null && !leaveMessageFormat.isEmpty()) {
            IPlayer iPlayer = platform.wrapPlayer(player);
            Text formattedMessage = services.getMessageParser().parseMessage(leaveMessageFormat, iPlayer).getOriginalText();
            platform.broadcastSystemMessage(formattedMessage);
        }
    }
}