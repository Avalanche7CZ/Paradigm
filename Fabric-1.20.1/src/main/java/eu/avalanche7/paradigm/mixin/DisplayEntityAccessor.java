package eu.avalanche7.paradigm.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DisplayEntity.class)
public interface DisplayEntityAccessor {
    @Invoker("setBillboardMode")
    void paradigm$setBillboardMode(DisplayEntity.BillboardMode mode);

    @Invoker("setViewRange")
    void paradigm$setViewRange(float range);
}
