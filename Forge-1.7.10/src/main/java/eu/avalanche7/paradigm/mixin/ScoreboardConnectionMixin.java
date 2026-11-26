package eu.avalanche7.paradigm.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.util.MessageSerializer;
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
        paradigm$scoreboard = ScoreboardConnection.installBeforePacketHandler(context.channel(), ScoreboardPacketCodec.INSTANCE);
    }

    @Override public ScoreboardConnection paradigm$scoreboard() { return paradigm$scoreboard; }

    @Mixin(MessageSerializer.class)
    public static abstract class Encoder {
        @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;Lio/netty/buffer/ByteBuf;)V",
                at = @At("RETURN"))
        private void paradigm$confirmScoreboardEncoding(ChannelHandlerContext context, Packet packet, ByteBuf buffer,
                CallbackInfo callback) {
            if (packet instanceof S01PacketJoinGame || packet instanceof S38PacketPlayerListItem || packet instanceof S3EPacketTeams) {
                ScoreboardConnection.confirmEncoded(context, packet);
            }
        }
    }
}
