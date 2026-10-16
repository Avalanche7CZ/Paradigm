package eu.avalanche7.paradigm.mixin;

import java.util.Collection;

import net.minecraft.network.play.server.S3EPacketTeams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(S3EPacketTeams.class)
public interface TeamPacketAccess {
    @Accessor("field_149320_a") String paradigm$name();
    @Accessor("field_149314_f") int paradigm$mode();
    @Accessor("field_149317_e") Collection<String> paradigm$members();
}
