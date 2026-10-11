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
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.commands.ListPlayersCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
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
        @Shadow(aliases = {"f_140472_"}, remap = false) @Final private Entity entity;
        @Shadow(aliases = {"m_140485_"}, remap = false) public abstract void removePlayer(ServerPlayer viewer);
        @Shadow(aliases = {"m_140497_"}, remap = false) public abstract void updatePlayer(ServerPlayer viewer);
    
        @Inject(method = "<init>", remap = false, at = @At("RETURN"))
        private void paradigm$bind(CallbackInfo ci) {
            if (entity instanceof ServerPlayer) VanishTracking.bind(entity, this);
        }
        @Inject(method = {"broadcastRemoved()V", "m_140482_()V"}, remap = false, at = @At("RETURN"))
        private void paradigm$unbind(CallbackInfo ci) { VanishTracking.unbind(entity, this); }
        @Inject(method = {"updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", "m_140497_(Lnet/minecraft/server/level/ServerPlayer;)V"}, remap = false, at = @At("HEAD"), cancellable = true)
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

    @Mixin(ServerCommonPacketListenerImpl.class)
    public static abstract class Connection {
        @Unique private final java.util.Set<java.util.UUID> paradigm$listed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        @ModifyVariable(method = {"send(Lnet/minecraft/network/protocol/Packet;)V", "m_295553_(Lnet/minecraft/network/protocol/Packet;)V"}, remap = false, at = @At("HEAD"), argsOnly = true)
        private Packet<?> paradigm$filter(Packet<?> packet) {
            return (Object) this instanceof ServerGamePacketListenerImpl handler ? VanishPackets.filter(packet, handler.player, paradigm$listed) : packet;
        }
    }

    @Mixin(ServerLoginPacketListenerImpl.class)
    public static abstract class Login {
        
        @Shadow(aliases = {"m_10053_"}, remap = false) public abstract void disconnect(Component reason);
        @Unique private CompletableFuture<VanishService.Admission> paradigm$admission;
    
        @Inject(method = {"verifyLoginAndFinishConnectionSetup", "m_294778_"}, remap = false, at = @At("HEAD"), cancellable = true)
        private void paradigm$admit(GameProfile profile, CallbackInfo ci) {
            var services = ParadigmAPI.getServices();
            if (services == null) return;
            String uuid = profile.id().toString();
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
        @Inject(method = {"remove", "m_11286_"}, remap = false, at = @At("RETURN"))
        private void paradigm$left(ServerPlayer player, CallbackInfo ci) {
            var services = ParadigmAPI.getServices();
            if (services != null) services.getVanishService().left(services.getPlatformAdapter().wrapPlayer(player));
        }
    }

    @Mixin(ClientboundPlayerInfoUpdatePacket.class)
    public static abstract class PlayerInfo implements eu.avalanche7.paradigm.platform.Interfaces.IVanishPlayerInfoPacket {
        @org.spongepowered.asm.mixin.Shadow(aliases = {"f_244436_"}, remap = false) @org.spongepowered.asm.mixin.Final @Mutable private List<ClientboundPlayerInfoUpdatePacket.Entry> entries;
        @SuppressWarnings("unchecked")
        @Override public void paradigm$entries(List<?> entries) { this.entries = (List<ClientboundPlayerInfoUpdatePacket.Entry>) entries; }
    }

    @Mixin(CommandSourceStack.class)
    public static abstract class Suggestions {
        @Inject(method = {"getOnlinePlayerNames", "m_5982_"}, remap = false, at = @At("RETURN"), cancellable = true)
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
            var players = status.players().map(value -> new ServerStatus.Players(value.max(), Math.max(0, value.online() - services.getVanishService().hiddenCount()),
                    value.sample().stream().filter(profile -> !services.getVanishService().isHidden(profile.id().toString())).toList()));
            return new ServerStatus(status.description(), players, status.version(), status.favicon(), status.enforcesSecureChat(), status.forgeData());
        }
    }

    @Mixin(ListPlayersCommand.class)
    public static abstract class OnlineList {
     @org.spongepowered.asm.mixin.injection.Group(name = "vanishList", min = 1)
     @Redirect(method = {"format", "m_137826_"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;", remap = false), remap = false, require = 0)
     private static List<ServerPlayer> paradigm$visible(PlayerList manager, CommandSourceStack source, Function<ServerPlayer, ?> formatter) {
      ServerPlayer viewer = source.getEntity() instanceof ServerPlayer player ? player : null;
      return manager.getPlayers().stream().filter(target -> VanishTracking.canSee(viewer, target)).toList();
     }
     @org.spongepowered.asm.mixin.injection.Group(name = "vanishList", min = 1)
     @Redirect(method = {"format", "m_137826_"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;m_11314_()Ljava/util/List;", remap = false), remap = false, require = 0)
     private static List<ServerPlayer> paradigm$visibleSrg(PlayerList manager, CommandSourceStack source, Function<ServerPlayer, ?> formatter) {
      ServerPlayer viewer = source.getEntity() instanceof ServerPlayer player ? player : null;
      return manager.getPlayers().stream().filter(target -> VanishTracking.canSee(viewer, target)).toList();
     }
    }
}
