package eu.avalanche7.paradigm.mixin;

import net.minecraft.world.entity.Interaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Interaction.class)
public interface InteractionAccessor {
    @Invoker(value = "setWidth")
    void paradigm$setWidth(float value);

    @Invoker(value = "setHeight")
    void paradigm$setHeight(float value);
}
