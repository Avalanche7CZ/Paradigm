package eu.avalanche7.paradigm.mixin;

import java.util.List;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraftforge.event.CommandEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import eu.avalanche7.paradigm.platform.CrucibleCommandBridge;

@Mixin(CommandHandler.class)
public abstract class LegacyCommandHandlerMixin {
    @Shadow
    private int getUsernameIndex(ICommand command, String[] parameters) {
        throw new AssertionError();
    }

    @Redirect(method = "executeCommand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/command/CommandHandler;getUsernameIndex(Lnet/minecraft/command/ICommand;[Ljava/lang/String;)I"))
    private int paradigm$bindInvocation(CommandHandler manager, ICommand command, String[] parameters,
            ICommandSender sender, String line) {
        CrucibleCommandBridge.bindInvocation(manager, sender, command, parameters);
        return getUsernameIndex(command, parameters);
    }

    @Redirect(method = "executeCommand", at = @At(value = "INVOKE",
            target = "Lcpw/mods/fml/common/eventhandler/EventBus;post(Lcpw/mods/fml/common/eventhandler/Event;)Z", remap = false))
    private boolean paradigm$guardCommand(EventBus bus, Event event) {
        return CrucibleCommandBridge.post((CommandHandler) (Object) this, bus, (CommandEvent) event);
    }

    @Inject(method = "registerCommand(Lnet/minecraft/command/ICommand;Ljava/lang/String;)Lnet/minecraft/command/ICommand;",
            at = @At(value = "INVOKE", target = "Lorg/bukkit/craftbukkit/v1_7_R4/command/CraftSimpleCommandMap;register(Ljava/lang/String;Lorg/bukkit/command/Command;)Z"),
            remap = false, require = 0, locals = LocalCapture.CAPTURE_FAILSOFT)
    private void paradigm$bindWrapper(ICommand command, String permission, CallbackInfoReturnable<ICommand> callback,
            List<?> aliases, @Coerce Object commandMap, @Coerce Object wrapper, String namespace) {
        CrucibleCommandBridge.bind(wrapper, command, permission, namespace);
    }
}
