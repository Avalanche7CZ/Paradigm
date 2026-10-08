package eu.avalanche7.paradigm.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.network.NetHandlerLoginServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.avalanche7.paradigm.platform.MinecraftLoginHandler;

@Mixin(NetHandlerLoginServer.class)
public abstract class NetHandlerLoginServerMixin {
    @Shadow
    private GameProfile field_147337_i;

    @Shadow
    @Final
    public NetworkManager field_147333_a;

    @Shadow
    protected abstract GameProfile func_152506_a(GameProfile profile);

    @Shadow
    public abstract void func_147322_a(String reason);

    @Inject(method = "func_147326_c", at = @At("HEAD"), cancellable = true)
    private void paradigm$checkAdmission(CallbackInfo callback) {
        if (!field_147337_i.isComplete()) {
            field_147337_i = func_152506_a(field_147337_i);
        }
        String rejection = MinecraftLoginHandler.rejection(field_147333_a.getSocketAddress(), field_147337_i);
        if (rejection != null) {
            func_147322_a(rejection);
            callback.cancel();
        }
    }
}
