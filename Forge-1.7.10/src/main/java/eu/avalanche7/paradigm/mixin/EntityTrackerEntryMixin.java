package eu.avalanche7.paradigm.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.avalanche7.paradigm.platform.visual.LegacyVisualController;

@Mixin(EntityTrackerEntry.class)
public abstract class EntityTrackerEntryMixin {
    @Shadow public Entity myEntity;

    @Inject(method = "tryStartWachingThis", at = @At("HEAD"), cancellable = true)
    private void paradigm$visibility(EntityPlayerMP viewer, CallbackInfo callback) {
        LegacyVisualController visuals = LegacyVisualController.current();
        if (visuals != null && !visuals.vanish().visible(myEntity, viewer)) callback.cancel();
    }
}
