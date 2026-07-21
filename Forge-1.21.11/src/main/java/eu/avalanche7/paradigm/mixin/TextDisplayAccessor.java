package eu.avalanche7.paradigm.mixin;

import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
    @Invoker(value = "setLineWidth", remap = false)
    void paradigm$setLineWidth(int value);

    @Invoker(value = "setTextOpacity", remap = false)
    void paradigm$setTextOpacity(byte value);

    @Invoker(value = "setFlags", remap = false)
    void paradigm$setFlags(byte value);
}
