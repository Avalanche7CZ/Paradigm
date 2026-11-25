package eu.avalanche7.paradigm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import eu.avalanche7.paradigm.platform.CrucibleCommandBridge;

@Pseudo
@Mixin(targets = "org.bukkit.craftbukkit.v1_7_R4.command.CraftSimpleCommandMap", remap = false)
public abstract class CrucibleCommandMapMixin {
    @Redirect(method = "dispatch(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z", at = @At(value = "INVOKE",
            target = "Lorg/bukkit/command/Command;testPermission(Lorg/bukkit/command/CommandSender;)Z"), require = 0)
    private boolean paradigm$authorizeWrapper(@Coerce Object wrapper, @Coerce Object sender,
            @Coerce Object dispatchSender, String line) {
        return CrucibleCommandBridge.testWrapper(wrapper, sender, line);
    }
}
