package eu.avalanche7.paradigm.mixin;

import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import eu.avalanche7.paradigm.modules.permissions.CommandPermissionElevation;

@Mixin(CommandSourceStack.class)
public class CommandSourcePermissionMixin {

    @Inject(method = "permissions", at = @At("RETURN"), cancellable = true, remap = false)
    private void paradigm$elevatePermissionLevel(CallbackInfoReturnable<net.minecraft.server.permissions.PermissionSet> cir) {
        if (CommandPermissionElevation.isElevated(this)) {
            var original = cir.getReturnValue();
            cir.setReturnValue(permission -> permission instanceof net.minecraft.server.permissions.Permission.HasCommandLevel
                    || permission.equals(net.minecraft.server.permissions.Permissions.COMMANDS_ENTITY_SELECTORS)
                    || original.hasPermission(permission));
        }
    }
}
