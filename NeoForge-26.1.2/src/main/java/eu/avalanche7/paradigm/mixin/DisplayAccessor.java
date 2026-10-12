package eu.avalanche7.paradigm.mixin;

import net.minecraft.world.entity.Display;
import com.mojang.math.Transformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("setBillboardConstraints")
    void paradigm$setBillboardConstraints(Display.BillboardConstraints constraints);

    @Invoker("setViewRange")
    void paradigm$setViewRange(float range);

    @Invoker("setTransformation")
    void paradigm$setTransformation(Transformation transformation);
}
