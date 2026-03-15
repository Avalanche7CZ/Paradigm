package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem.ChatEventListener;
import eu.avalanche7.paradigm.platform.MinecraftEventSystem;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket")
interface ChatMessageC2SPacketAccessor {
    @Accessor("chatMessage")
    void paradigm$setChatMessage(String message);
}

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

        Services services = ParadigmAPI.getServices();

        if (event.isCancelled()) {
            if (services != null && services.getDebugLogger() != null) {
                services.getDebugLogger().debugLog("[Chat] Message cancelled for " + player.getGameProfile().getName() + ": '" + message + "'");
            }
            ci.cancel();
            return;
        }

        if (!event.getMessage().equals(message)) {
            try {
                ((ChatMessageC2SPacketAccessor) (Object) packet).paradigm$setChatMessage(event.getMessage());
                if (services != null && services.getDebugLogger() != null) {
                    services.getDebugLogger().debugLog("[Chat] Message modified for " + player.getGameProfile().getName() + ": '" + message + "' -> '" + event.getMessage() + "'");
                }
            } catch (Throwable t) {
                if (services != null && services.getDebugLogger() != null) {
                    services.getDebugLogger().debugLog("[Chat] Failed to mutate chat packet: " + t);
                }
            }
        }
    }
}
