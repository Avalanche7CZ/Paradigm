package eu.avalanche7.paradigm.mixin;

import net.minecraft.network.NetHandlerPlayServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NetHandlerPlayServer.class)
public interface PlayerCommandAccess {
    @Invoker("handleSlashCommand")
    void paradigm$handleSlashCommand(String line);
}
