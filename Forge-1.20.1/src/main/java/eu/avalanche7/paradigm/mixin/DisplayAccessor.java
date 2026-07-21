package eu.avalanche7.paradigm.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker(value = "setTransformation", remap = false)
    void paradigm$setTransformation(Transformation transformation);
}
