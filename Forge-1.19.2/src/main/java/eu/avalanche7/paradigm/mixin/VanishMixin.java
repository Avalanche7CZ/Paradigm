package eu.avalanche7.paradigm.mixin;

import com.mojang.authlib.GameProfile;
import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.modules.vanish.VanishService;
import eu.avalanche7.paradigm.platform.Interfaces.IVanishTracker;
import eu.avalanche7.paradigm.platform.VanishPackets;
import eu.avalanche7.paradigm.platform.VanishTracking;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.commands.ListPlayersCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class VanishMixin {
    private VanishMixin() {}

    @Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
    public static abstract class Tracker implements IVanishTracker {
        @Shadow @Final private Entity entity;
        @Shadow public abstract void removePlayer(ServerPlayer viewer);
        @Shadow public abstract void updatePlayer(ServerPlayer viewer);
    
        @Inject(method = "<init>", at = @At("RETURN"))
        private void paradigm$bind(CallbackInfo ci) {
            if (entity instanceof ServerPlayer) VanishTracking.bind(entity, this);
        }
        @Inject(method = "broadcastRemoved()V", at = @At("RETURN"))
        private void paradigm$unbind(CallbackInfo ci) { VanishTracking.unbind(entity, this); }
        @Inject(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
        private void paradigm$gate(ServerPlayer viewer, CallbackInfo ci) {
            if (entity instanceof ServerPlayer && !VanishTracking.canSee(viewer, entity)) {
                removePlayer(viewer);
                ci.cancel();
            }
        }
        @Override public void paradigm$refreshVisibility(Object viewer) {
            if (viewer instanceof ServerPlayer player) {
                if (VanishTracking.canSee(player, entity)) updatePlayer(player); else removePlayer(player);
            }
        }
    }

    @Mixin(ServerGamePacketListenerImpl.class)
    public static abstract class Connection {
        @Unique private final java.util.Set<java.util.UUID> paradigm$listed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), argsOnly = true)
        private Packet<?> paradigm$filter(Packet<?> packet) {
            return (Object) this instanceof ServerGamePacketListenerImpl handler ? VanishPackets.filter(packet, handler.player, paradigm$listed) : packet;
        }
    }

    @Mixin(ServerLoginPacketListenerImpl.class)
    public static abstract class Login {
        @Shadow private GameProfile gameProfile;
        @Shadow public abstract void disconnect(Component reason);
        @Unique private CompletableFuture<VanishService.Admission> paradigm$admission;
    
        @Inject(method = "handleAcceptedLogin", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;canPlayerLogin(Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/network/chat/Component;"), cancellable = true)
        private void paradigm$admit(CallbackInfo ci) {
            var services = ParadigmAPI.getServices();
            if (services == null) return;
            String uuid = gameProfile.getId().toString();
            if (paradigm$admission == null) paradigm$admission = services.getVanishService().loadAdmission(uuid);
            if (!paradigm$admission.isDone()) { ci.cancel(); return; }
            try {
                var admission = paradigm$admission.join();
                if (!services.getVanishService().isCurrent(admission)) {
                    paradigm$admission = null;
                    ci.cancel();
                    return;
                }
                services.getVanishService().prepare(uuid, admission);
            } catch (CompletionException failure) {
                disconnect(Component.literal("Unable to restore player state. Please retry."));
                ci.cancel();
            }
        }
    }

    @Mixin(PlayerList.class)
    public static abstract class Lifecycle {
        @Inject(method = "remove", at = @At("RETURN"))
        private void paradigm$left(ServerPlayer player, CallbackInfo ci) {
            var services = ParadigmAPI.getServices();
            if (services != null) services.getVanishService().left(services.getPlatformAdapter().wrapPlayer(player));
        }
    }

    @Mixin(ClientboundPlayerInfoPacket.class)
    public static abstract class PlayerInfo implements eu.avalanche7.paradigm.platform.Interfaces.IVanishPlayerInfoPacket {
        @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final @Mutable private List<ClientboundPlayerInfoPacket.PlayerUpdate> entries;
        @SuppressWarnings("unchecked")
        @Override public void paradigm$entries(List<?> entries) { this.entries = (List<ClientboundPlayerInfoPacket.PlayerUpdate>) entries; }
    }

    @Mixin(CommandSourceStack.class)
    public static abstract class Suggestions {
        @Inject(method = "getOnlinePlayerNames", at = @At("RETURN"), cancellable = true)
        private void paradigm$names(CallbackInfoReturnable<Collection<String>> ci) {
            var services = ParadigmAPI.getServices();
            if (services == null) return;
            var source = services.getPlatformAdapter().wrapCommandSource(this);
            ci.setReturnValue(services.getPlatformAdapter().getVisiblePlayerNames(source.getPlayer()));
        }
    }

    @Mixin(ClientboundStatusResponsePacket.class)
    public static abstract class Status {
        @ModifyVariable(method = "<init>(Lnet/minecraft/network/protocol/status/ServerStatus;)V", at = @At("HEAD"), argsOnly = true)
        private static ServerStatus paradigm$publicStatus(ServerStatus status) {
            var services = ParadigmAPI.getServices();
            if (services == null || services.getVanishService().hiddenCount() == 0) return status;
            var players = status.getPlayers();
            if (players == null) return status;
            var filtered = new ServerStatus();
            filtered.setDescription(status.getDescription());
            filtered.setVersion(status.getVersion());
            filtered.setFavicon(status.getFavicon());
            filtered.setForgeData(status.getForgeData());
            var replacement = new ServerStatus.Players(players.getMaxPlayers(), Math.max(0, players.getNumPlayers() - services.getVanishService().hiddenCount()));
            if (players.getSample() != null) replacement.setSample(java.util.Arrays.stream(players.getSample())
                    .filter(profile -> !services.getVanishService().isHidden(profile.getId().toString())).toArray(com.mojang.authlib.GameProfile[]::new));
            filtered.setPlayers(replacement);
            return filtered;
        }
    }

    @Mixin(ListPlayersCommand.class)
    public static abstract class OnlineList {
     @Redirect(method = "format", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"))
     private static List<ServerPlayer> paradigm$visible(PlayerList manager, CommandSourceStack source, Function<ServerPlayer, ?> formatter) {
      return manager.getPlayers().stream().filter(target -> VanishTracking.canSee(source.getEntity(), target)).toList();
     }
    }
}
