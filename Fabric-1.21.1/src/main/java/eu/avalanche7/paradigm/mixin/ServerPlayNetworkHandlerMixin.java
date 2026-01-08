package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem.ChatEventListener;
import eu.avalanche7.paradigm.platform.MinecraftEventSystem;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {
    @Inject(method = "onChatMessage", at = @At("HEAD"), cancellable = true)
    private void onChatMessage(net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket packet, CallbackInfo ci) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler)(Object)this;
        ServerPlayerEntity player = handler.getPlayer();
        String message = packet.chatMessage();
        MinecraftEventSystem.ChatEventImpl event = new MinecraftEventSystem.ChatEventImpl(player, message);
        for (ChatEventListener listener : MinecraftEventSystem.getChatListeners()) {
            try {
                listener.onPlayerChat(event);
            } catch (Exception e) {
                System.err.println("Error in chat event listener: " + e.getMessage());
            }
        }

        if (event.isCancelled()) {
            ci.cancel();
        } else if (!event.getMessage().equals(message)) {
            player.sendMessage(Text.literal(event.getMessage()));
            ci.cancel();
        }
    }
}
