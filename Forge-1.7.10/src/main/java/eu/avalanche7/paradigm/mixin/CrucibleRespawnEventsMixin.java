package eu.avalanche7.paradigm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.avalanche7.paradigm.platform.LegacyRespawnPlacement;

@Pseudo
@Mixin(targets = "org.bukkit.plugin.SimplePluginManager", remap = false)
public abstract class CrucibleRespawnEventsMixin {
    @Inject(method = "callEvent(Lorg/bukkit/event/Event;)V", at = @At("HEAD"))
    private void paradigm$beforeRespawnEvent(@Coerce Object event, CallbackInfo callback) {
        LegacyRespawnPlacement.beforeBukkitEvent(event);
    }

    @Inject(method = "callEvent(Lorg/bukkit/event/Event;)V", at = @At("RETURN"))
    private void paradigm$afterRespawnEvent(@Coerce Object event, CallbackInfo callback) {
        LegacyRespawnPlacement.afterBukkitEvent(event);
    }
}
