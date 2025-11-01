package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public class PlayerManagerMixin {

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
    private void paradigm$maybeSuppressBroadcast(Component message, boolean overlay, CallbackInfo ci) {
        if (message == null) return;
        Services services = Paradigm.getServices();
        if (services == null) return;
        ChatConfigHandler.Config chatConfig = services.getChatConfig();
        if (chatConfig == null) return;
        if (message.getContents() instanceof TranslatableContents tc) {
            String key = tc.getKey();
            if ("multiplayer.player.joined".equals(key) || "multiplayer.player.joined.renamed".equals(key)) {
                if (chatConfig.enableJoinLeaveMessages.get() || chatConfig.enableFirstJoinMessage.get()) {
                    ci.cancel();
                }
                return;
            }
            if ("multiplayer.player.left".equals(key)) {
                if (chatConfig.enableJoinLeaveMessages.get()) {
                    ci.cancel();
                }
            }
        }
    }
}