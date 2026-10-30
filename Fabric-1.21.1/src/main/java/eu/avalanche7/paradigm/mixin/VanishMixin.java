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
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.query.QueryResponseS2CPacket;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.ServerMetadata;
import net.minecraft.server.command.ListCommand;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
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

    @Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
    public static abstract class Tracker implements IVanishTracker {
        @Shadow @Final private Entity entity;
        @Shadow public abstract void stopTracking(ServerPlayerEntity viewer);
        @Shadow public abstract void updateTrackedStatus(ServerPlayerEntity viewer);
    
        @Inject(method = "<init>", at = @At("RETURN"))
        private void paradigm$bind(CallbackInfo ci) {
            if (entity instanceof ServerPlayerEntity) VanishTracking.bind(entity, this);
        }
        @Inject(method = "stopTracking()V", at = @At("RETURN"))
        private void paradigm$unbind(CallbackInfo ci) { VanishTracking.unbind(entity, this); }
        @Inject(method = "updateTrackedStatus(Lnet/minecraft/server/network/ServerPlayerEntity;)V", at = @At("HEAD"), cancellable = true)
        private void paradigm$gate(ServerPlayerEntity viewer, CallbackInfo ci) {
            if (entity instanceof ServerPlayerEntity && !VanishTracking.canSee(viewer, entity)) {
                stopTracking(viewer);
                ci.cancel();
            }
        }
        @Override public void paradigm$refreshVisibility(Object viewer) {
            if (viewer instanceof ServerPlayerEntity player) {
                if (player.getWorld() == entity.getWorld() && VanishTracking.canSee(player, entity)) updateTrackedStatus(player); else stopTracking(player);
            }
        }
    }

    @Mixin(ServerCommonNetworkHandler.class)
    public static abstract class Connection {
        @Unique private final java.util.Set<java.util.UUID> paradigm$listed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        @ModifyVariable(method = "sendPacket(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), argsOnly = true)
        private Packet<?> paradigm$filter(Packet<?> packet) {
            if ((Object) this instanceof ServerPlayNetworkHandler handler) {
                var services = ParadigmAPI.getServices();
                var viewer = handler.getPlayer();
                if (services != null && packet instanceof net.minecraft.network.packet.s2c.play.TeamS2CPacket team) {
                    int mode = team.getTeamOperation() == net.minecraft.network.packet.s2c.play.TeamS2CPacket.Operation.ADD ? 0
                            : team.getTeamOperation() == net.minecraft.network.packet.s2c.play.TeamS2CPacket.Operation.REMOVE ? 1
                            : team.getPlayerListOperation() == net.minecraft.network.packet.s2c.play.TeamS2CPacket.Operation.ADD ? 3
                            : team.getPlayerListOperation() == net.minecraft.network.packet.s2c.play.TeamS2CPacket.Operation.REMOVE ? 4 : 2;
                    eu.avalanche7.paradigm.modules.tab.Tablist.observeSortingTeam(services.getPlatformAdapter().wrapPlayer(viewer), team.getTeamName(), mode, team.getPlayerNames());
                }
            }
            return (Object) this instanceof ServerPlayNetworkHandler handler ? VanishPackets.filter(packet, handler.getPlayer(), paradigm$listed) : packet;
        }
    }

    @Mixin(ServerLoginNetworkHandler.class)
    public static abstract class Login {
        
        @Shadow public abstract void disconnect(Text reason);
        @Unique private CompletableFuture<VanishService.Admission> paradigm$admission;
    
        @Inject(method = "tickVerify", at = @At("HEAD"), cancellable = true)
        private void paradigm$admit(GameProfile profile, CallbackInfo ci) {
            var services = ParadigmAPI.getServices();
            if (services == null) return;
            String uuid = profile.getId().toString();
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
                disconnect(Text.literal("Unable to restore player state. Please retry."));
                ci.cancel();
            }
        }
    }

    @Mixin(PlayerManager.class)
    public static abstract class Lifecycle {
        @Inject(method = "remove", at = @At("HEAD"))
        private void paradigm$dispatchLeave(ServerPlayerEntity player, CallbackInfo ci) {
            var services = ParadigmAPI.getServices();
            if (services != null && services.getPlatformAdapter().getEventSystem() instanceof eu.avalanche7.paradigm.platform.MinecraftEventSystem events) events.dispatchLeave(player);
        }
    
        @Inject(method = "remove", at = @At("RETURN"))
        private void paradigm$left(ServerPlayerEntity player, CallbackInfo ci) {
            var services = ParadigmAPI.getServices();
            if (services != null) services.getVanishService().left(services.getPlatformAdapter().wrapPlayer(player));
        }
    }

    @Mixin(PlayerListS2CPacket.class)
    public static abstract class PlayerInfo implements eu.avalanche7.paradigm.platform.Interfaces.IVanishPlayerInfoPacket {
        @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final @Mutable private List<PlayerListS2CPacket.Entry> entries;
        @SuppressWarnings("unchecked")
        @Override public void paradigm$entries(List<?> entries) { this.entries = (List<PlayerListS2CPacket.Entry>) entries; }
    }

    @Mixin(ServerCommandSource.class)
    public static abstract class Suggestions {
        @Inject(method = "getPlayerNames", at = @At("RETURN"), cancellable = true)
        private void paradigm$names(CallbackInfoReturnable<Collection<String>> ci) {
            var services = ParadigmAPI.getServices();
            if (services == null) return;
            var source = services.getPlatformAdapter().wrapCommandSource(this);
            ci.setReturnValue(services.getPlatformAdapter().getVisiblePlayerNames(source.getPlayer()));
        }
    }

    @Mixin(QueryResponseS2CPacket.class)
    public static abstract class Status {
        @ModifyVariable(method = "<init>(Lnet/minecraft/server/ServerMetadata;)V", at = @At("HEAD"), argsOnly = true)
        private static ServerMetadata paradigm$publicStatus(ServerMetadata status) {
            var services = ParadigmAPI.getServices();
            if (services == null || services.getVanishService().hiddenCount() == 0) return status;
            var players = status.players().map(value -> new ServerMetadata.Players(value.max(), Math.max(0, value.online() - services.getVanishService().hiddenCount()),
                    value.sample().stream().filter(profile -> !services.getVanishService().isHidden(profile.getId().toString())).toList()));
            return new ServerMetadata(status.description(), players, status.version(), status.favicon(), status.secureChatEnforced());
        }
    }

    @Mixin(ListCommand.class)
    public static abstract class OnlineList {
     @Redirect(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;getPlayerList()Ljava/util/List;"))
     private static List<ServerPlayerEntity> paradigm$visible(PlayerManager manager, ServerCommandSource source, Function<ServerPlayerEntity, ?> formatter) {
      return manager.getPlayerList().stream().filter(target -> VanishTracking.canSee(source.getPlayer(), target)).toList();
     }
    }
}
