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
}
