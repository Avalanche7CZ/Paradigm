package eu.avalanche7.paradigm.mixin;

import java.util.UUID;

import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import eu.avalanche7.paradigm.modules.permissions.CommandNodeAccessGate;
import eu.avalanche7.paradigm.modules.permissions.CommandPermissionElevation;

@Mixin(value = CommandNode.class, remap = false)
public class CommandNodePermissionMixin {

    @Inject(method = "canUse", at = @At("HEAD"), remap = false)
    private void paradigm$beginPermissionScope(Object source, CallbackInfoReturnable<Boolean> cir) {
        CommandPermissionElevation.clear();
        UUID uuid = paradigm$playerUuid(source);
        if (uuid == null) {
            return;
        }
        Boolean decision = CommandNodeAccessGate.decide(this, uuid);
        if (decision != null) {
            CommandPermissionElevation.begin(source, decision);
        }
    }

    @Inject(method = "canUse", at = @At("RETURN"), cancellable = true, remap = false)
    private void paradigm$endPermissionScope(Object source, CallbackInfoReturnable<Boolean> cir) {
        if (CommandPermissionElevation.isDenied(source)) {
            cir.setReturnValue(false);
        }
        CommandPermissionElevation.clear();
    }

    private static UUID paradigm$playerUuid(Object source) {
        if (!(source instanceof ServerCommandSource stack)) {
            return null;
        }
        Entity entity = stack.getEntity();
        return entity instanceof ServerPlayerEntity player ? player.getUuid() : null;
    }
}
