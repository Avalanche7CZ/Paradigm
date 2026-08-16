package eu.avalanche7.paradigm.mixin;

import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import eu.avalanche7.paradigm.modules.permissions.CommandPermissionElevation;

@Mixin(ServerCommandSource.class)
public class CommandSourcePermissionMixin {

    @Inject(method = "*(I)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void paradigm$elevatePermissionLevel(int level, CallbackInfoReturnable<Boolean> cir) {
        if (CommandPermissionElevation.isElevated(this)) {
            cir.setReturnValue(true);
        }
    }
}
