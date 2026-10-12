package eu.avalanche7.paradigm.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
    @Invoker("setText")
    void paradigm$setText(Component text);

    @Invoker("setBackgroundColor")
    void paradigm$setBackgroundColor(int background);

    @Invoker("setLineWidth")
    void paradigm$setLineWidth(int value);

    @Invoker("setTextOpacity")
    void paradigm$setTextOpacity(byte value);

    @Invoker("setFlags")
    void paradigm$setFlags(byte value);
}
