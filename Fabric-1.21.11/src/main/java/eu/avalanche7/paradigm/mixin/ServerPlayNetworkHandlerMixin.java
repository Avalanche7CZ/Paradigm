package eu.avalanche7.paradigm.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.logging.LogUtils;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem.ChatEventListener;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem.PlayerCommandEventListener;
import eu.avalanche7.paradigm.platform.MinecraftEventSystem;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {
    @Unique
    private static final Logger PARADIGM_LOGGER = LogUtils.getLogger();

    @Redirect(method = {"executeCommand", "handleCommandExecution"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/command/CommandManager;execute(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V"), require = 2, allow = 2)
    private void paradigm$executeCommand(CommandManager manager, ParseResults<ServerCommandSource> parsed, String command) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        ServerPlayerEntity player = handler.getPlayer();
        if (!paradigm$canDispatch(handler, player)) {
            return;
        }

        MinecraftEventSystem.CommandEventImpl event = new MinecraftEventSystem.CommandEventImpl(player, command);
        boolean cancelled = false;
        for (PlayerCommandEventListener listener : MinecraftEventSystem.getCommandListeners()) {
            if (!paradigm$canDispatch(handler, player)) {
                return;
            }
            try {
                listener.onPlayerCommand(event);
            } catch (Exception | LinkageError failure) {
                PARADIGM_LOGGER.error("[Paradigm] Command listener failed; refusing command execution.", failure);
                event.setCancelled(true);
            }
            cancelled |= event.isCancelled();
            event.setCancelled(cancelled);
        }
        if (cancelled || !paradigm$canDispatch(handler, player)) {
            return;
        }
        manager.execute(parsed, command);
    }

    @Redirect(method = "handleDecoratedMessage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V"))
    private void paradigm$broadcastChat(PlayerManager manager, SignedMessage message, ServerPlayerEntity player, MessageType.Parameters params) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        if (!paradigm$canDispatch(handler, player)) {
            return;
        }

        String original = message.getSignedContent();
        MinecraftEventSystem.ChatEventImpl event = new MinecraftEventSystem.ChatEventImpl(player, original);
        for (ChatEventListener listener : MinecraftEventSystem.getChatListeners()) {
            if (!paradigm$canDispatch(handler, player)) {
                return;
            }
            try {
                listener.onPlayerChat(event);
            } catch (Exception | LinkageError failure) {
                PARADIGM_LOGGER.error("[Paradigm] Chat listener failed; refusing chat broadcast.", failure);
                event.setCancelled(true);
            }
            if (event.isCancelled()) return;
        }
        if (!paradigm$canDispatch(handler, player)) {
            return;
        }
        if (!original.equals(event.getMessage())) {
            message = message.withUnsignedContent(Text.literal(event.getMessage()));
        }
        manager.broadcast(message, player, params);
    }

    @Unique
    private boolean paradigm$canDispatch(ServerPlayNetworkHandler handler, ServerPlayerEntity player) {
        MinecraftServer server = player.getCommandSource().getServer();
        if (!server.isOnThread()) {
            throw new IllegalStateException("Paradigm command/chat callbacks require the server thread");
        }
        return handler.isConnectionOpen()
                && player.networkHandler == handler
                && server.getPlayerManager().getPlayer(player.getUuid()) == player;
    }
}
