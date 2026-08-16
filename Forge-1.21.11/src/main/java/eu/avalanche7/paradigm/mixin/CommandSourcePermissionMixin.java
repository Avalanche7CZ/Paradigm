package eu.avalanche7.paradigm.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import eu.avalanche7.paradigm.modules.permissions.CommandPermissionElevation;

@Mixin(CommandSourceStack.class)
public class CommandSourcePermissionMixin {

    @Inject(method = "*()Lnet/minecraft/server/permissions/PermissionSet;", at = @At("RETURN"), cancellable = true, remap = false)
    private void paradigm$elevatePermissionSet(CallbackInfoReturnable<PermissionSet> cir) {
        if (CommandPermissionElevation.isElevated(this)) {
            cir.setReturnValue(permission -> true);
        }
    }
}
