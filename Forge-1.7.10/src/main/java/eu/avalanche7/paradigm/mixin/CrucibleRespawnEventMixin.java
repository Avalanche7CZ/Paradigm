package eu.avalanche7.paradigm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.avalanche7.paradigm.platform.LegacyRespawnPlacement;

@Pseudo
@Mixin(targets = "org.bukkit.event.player.PlayerRespawnEvent", remap = false)
public abstract class CrucibleRespawnEventMixin {
    @Inject(method = "setRespawnLocation(Lorg/bukkit/Location;)V", at = @At("RETURN"))
    private void paradigm$destinationSelected(CallbackInfo callback) {
        LegacyRespawnPlacement.bukkitDestinationSet(this);
    }
}
