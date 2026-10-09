package eu.avalanche7.paradigm.mixin;

import net.minecraft.network.play.server.S38PacketPlayerListItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(S38PacketPlayerListItem.class)
public interface PlayerListPacketAccess {
    @Accessor("field_149126_a")
    String paradigm$name();
    @Accessor("field_149124_b")
    boolean paradigm$online();
}
