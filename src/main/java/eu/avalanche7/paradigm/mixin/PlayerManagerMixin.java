package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    @Redirect(method = "onPlayerConnect", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V"))
    private void suppressDefaultJoinMessage(PlayerManager instance, Text message, boolean overlay) {
        if (message.getContent() instanceof TranslatableTextContent && ((TranslatableTextContent) message.getContent()).getKey().equals("multiplayer.player.joined")) {
            return;
        }
        instance.broadcast(message, overlay);
    }

    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    private void sendCustomJoinMessage(ClientConnection connection, ServerPlayerEntity player, CallbackInfo ci) {
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