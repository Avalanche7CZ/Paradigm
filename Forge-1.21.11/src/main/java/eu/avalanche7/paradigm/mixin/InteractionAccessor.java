package eu.avalanche7.paradigm.mixin;

import net.minecraft.world.entity.Interaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Interaction.class)
public interface InteractionAccessor {
    @Invoker(value = "setWidth", remap = false)
    void paradigm$setWidth(float value);

    @Invoker(value = "setHeight", remap = false)
    void paradigm$setHeight(float value);
}
