package eu.avalanche7.paradigm.platform;

import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;

import eu.avalanche7.paradigm.mixin.PlayerListPacketAccess;
import eu.avalanche7.paradigm.mixin.TeamPacketAccess;
import eu.avalanche7.paradigm.modules.tab.ScoreboardConnection;

public enum ScoreboardPacketCodec implements ScoreboardConnection.Codec {
    INSTANCE;

    @Override public ScoreboardConnection.Change team(Object packet) {
        if (!(packet instanceof S3EPacketTeams)) return null;
        var team = (TeamPacketAccess) packet;
        return new ScoreboardConnection.Change(team.paradigm$name(), team.paradigm$mode(), team.paradigm$members());
    }

    @Override public boolean opensScoreboard(Object packet) { return packet instanceof S01PacketJoinGame; }

    @Override public ScoreboardConnection.Listing listing(Object packet) {
        if (!(packet instanceof S38PacketPlayerListItem)) return null;
        var item = (PlayerListPacketAccess) packet;
        return new ScoreboardConnection.Listing(item.paradigm$name(), item.paradigm$online());
    }

    @Override public Object remove(String name) {
        return new S3EPacketTeams(new ScorePlayerTeam(new Scoreboard(), name), ScoreboardConnection.REMOVE);
    }

}
