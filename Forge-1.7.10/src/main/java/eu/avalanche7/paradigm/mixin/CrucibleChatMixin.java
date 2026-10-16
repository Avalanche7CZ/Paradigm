package eu.avalanche7.paradigm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.avalanche7.paradigm.platform.CrucibleChatBridge;

@Pseudo
@Mixin(targets = "org.bukkit.plugin.SimplePluginManager", remap = false)
public abstract class CrucibleChatMixin {
    @Inject(method = "callEvent(Lorg/bukkit/event/Event;)V", at = @At("RETURN"), remap = false)
    private void paradigm$formattedChat(@Coerce Object event, CallbackInfo callback) {
        CrucibleChatBridge.afterChatEvent(event);
    }
}
