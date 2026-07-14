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

    @Override
    public void paradigm$setTablistDisplayName(Object displayName) {
        this.paradigm$tablistDisplayName = displayName instanceof Text text ? text : null;
    }

    @Override
    public void paradigm$setTablistOrder(int order) {
        // Native player-list ordering is not present in this protocol version.
    }

    @Inject(method = "getPlayerListName", at = @At("HEAD"), cancellable = true)
    private void paradigm$replaceTablistDisplayName(CallbackInfoReturnable<Text> cir) {
        if (paradigm$tablistDisplayName != null) cir.setReturnValue(paradigm$tablistDisplayName);
    }
}
