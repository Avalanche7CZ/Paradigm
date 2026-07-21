package eu.avalanche7.paradigm.mixin;

import net.minecraft.entity.decoration.InteractionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(InteractionEntity.class)
public interface InteractionEntityAccessor {
    @Invoker("setInteractionWidth")
    void paradigm$setInteractionWidth(float value);

    @Invoker("setInteractionHeight")
    void paradigm$setInteractionHeight(float value);

}
