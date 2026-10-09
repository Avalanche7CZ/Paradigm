package eu.avalanche7.paradigm.mixin;

import net.minecraft.network.play.client.C0DPacketCloseWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(C0DPacketCloseWindow.class)
public interface CloseWindowPacketAccess {
    @Accessor("field_149556_a") int paradigm$windowId();
}
