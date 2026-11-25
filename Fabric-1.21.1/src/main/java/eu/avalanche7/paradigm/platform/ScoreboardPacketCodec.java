package eu.avalanche7.paradigm.platform;

import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.TeamS2CPacket;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.Scoreboard;

import eu.avalanche7.paradigm.modules.tab.ScoreboardConnection;

public enum ScoreboardPacketCodec implements ScoreboardConnection.Codec {
    INSTANCE;

    @Override public ScoreboardConnection.Change team(Object packet) {
        if (!(packet instanceof TeamS2CPacket team)) return null;
        int mode = team.getTeamOperation() == TeamS2CPacket.Operation.ADD ? ScoreboardConnection.CREATE
                : team.getTeamOperation() == TeamS2CPacket.Operation.REMOVE ? ScoreboardConnection.REMOVE
                : team.getPlayerListOperation() == TeamS2CPacket.Operation.ADD ? ScoreboardConnection.ADD
                : team.getPlayerListOperation() == TeamS2CPacket.Operation.REMOVE ? ScoreboardConnection.LEAVE
                : ScoreboardConnection.UPDATE;
        return new ScoreboardConnection.Change(team.getTeamName(), mode, team.getPlayerNames());
    }

    @Override public boolean opensScoreboard(Object packet) { return packet instanceof GameJoinS2CPacket; }

    @Override public Object remove(String name) {
        return TeamS2CPacket.updateRemovedTeam(new Team(new Scoreboard(), name));
    }

}
