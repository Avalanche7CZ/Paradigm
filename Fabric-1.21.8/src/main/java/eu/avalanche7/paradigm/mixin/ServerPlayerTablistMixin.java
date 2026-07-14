package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.platform.Interfaces.ITablistPlayerAccess;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerTablistMixin implements ITablistPlayerAccess {
    @Unique private Text paradigm$tablistDisplayName;
    @Unique private int paradigm$tablistOrder;
    @Override public void paradigm$setTablistDisplayName(Object value) { paradigm$tablistDisplayName = value instanceof Text text ? text : null; }
    @Override public void paradigm$setTablistOrder(int order) { paradigm$tablistOrder = order; }
    @Inject(method = "getPlayerListName", at = @At("HEAD"), cancellable = true)
    private void paradigm$displayName(CallbackInfoReturnable<Text> cir) {
        if (paradigm$tablistDisplayName != null) cir.setReturnValue(paradigm$tablistDisplayName);
    }
    @Inject(method = "getPlayerListOrder", at = @At("HEAD"), cancellable = true)
    private void paradigm$order(CallbackInfoReturnable<Integer> cir) { cir.setReturnValue(paradigm$tablistOrder); }
}
