package eu.avalanche7.paradigm.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DisplayEntity.TextDisplayEntity.class)
public interface TextDisplayEntityAccessor {
    @Invoker("setText")
    void paradigm$setText(Text text);

    @Invoker("setBackground")
    void paradigm$setBackground(int background);

    @Invoker("setLineWidth")
    void paradigm$setLineWidth(int width);

    @Invoker("setTextOpacity")
    void paradigm$setTextOpacity(byte opacity);

    @Invoker("setDisplayFlags")
    void paradigm$setDisplayFlags(byte flags);
}
