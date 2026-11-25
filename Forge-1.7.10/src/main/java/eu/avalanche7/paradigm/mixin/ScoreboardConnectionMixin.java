package eu.avalanche7.paradigm.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.avalanche7.paradigm.modules.tab.ScoreboardConnection;
import eu.avalanche7.paradigm.platform.ScoreboardPacketCodec;

@Mixin(NetworkManager.class)
public abstract class ScoreboardConnectionMixin implements ScoreboardConnection.Access {
    @Unique private ScoreboardConnection paradigm$scoreboard;
    @Inject(method = "channelActive", at = @At("RETURN"), remap = false)
    private void paradigm$trackClientScoreboard(ChannelHandlerContext context, CallbackInfo callback) {
        paradigm$scoreboard = ScoreboardConnection.install(context.channel(), ScoreboardPacketCodec.INSTANCE);
    }

    @Override public ScoreboardConnection paradigm$scoreboard() { return paradigm$scoreboard; }
}
