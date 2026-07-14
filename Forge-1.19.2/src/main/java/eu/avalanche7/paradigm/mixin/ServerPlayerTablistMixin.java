package eu.avalanche7.paradigm.mixin;
import eu.avalanche7.paradigm.platform.Interfaces.ITablistPlayerAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTablistMixin implements ITablistPlayerAccess {
    @Unique private Component paradigm$tablistName;
    @Override public void paradigm$setTablistDisplayName(Object value) { paradigm$tablistName = value instanceof Component component ? component : null; }
    @Override public void paradigm$setTablistOrder(int order) { }
    @Inject(method="getTabListDisplayName", at=@At("HEAD"), cancellable=true, remap=false)
    private void paradigm$name(CallbackInfoReturnable<Component> cir) { if (paradigm$tablistName != null) cir.setReturnValue(paradigm$tablistName); }
}
