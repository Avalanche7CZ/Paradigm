package eu.avalanche7.paradigm.mixin;

import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import eu.avalanche7.paradigm.modules.permissions.CommandPermissionElevation;

@Mixin(ServerCommandSource.class)
public class CommandSourcePermissionMixin {

    @Inject(method = "getPermissions", at = @At("RETURN"), cancellable = true)
    private void paradigm$elevatePermissionSet(CallbackInfoReturnable<PermissionPredicate> cir) {
        if (CommandPermissionElevation.isElevated(this)) {
            cir.setReturnValue(permission -> true);
        }
    }
}
