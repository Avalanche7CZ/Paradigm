package eu.avalanche7.paradigm.mixin;

import net.minecraft.network.ServerStatusResponse;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.NetHandlerStatusServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.platform.visual.LegacyServerStatus;

@Mixin(NetHandlerStatusServer.class)
public abstract class ServerStatusMixin {
    @Redirect(method = "processServerQuery", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;func_147134_at()Lnet/minecraft/network/ServerStatusResponse;"))
    private ServerStatusResponse paradigm$status(MinecraftServer server) {
        return LegacyServerStatus.customize(server.func_147134_at(), ParadigmAPI.getServices());
    }
}
