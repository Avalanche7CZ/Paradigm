package eu.avalanche7.paradigm.platform;

import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import eu.avalanche7.paradigm.modules.tab.ScoreboardConnection;

public enum ScoreboardPacketCodec implements ScoreboardConnection.Codec {
    INSTANCE;

    @Override public ScoreboardConnection.Change team(Object packet) {
        if (!(packet instanceof ClientboundSetPlayerTeamPacket team)) return null;
        int mode = team.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD ? ScoreboardConnection.CREATE
                : team.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE ? ScoreboardConnection.REMOVE
                : team.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD ? ScoreboardConnection.ADD
                : team.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE ? ScoreboardConnection.LEAVE
                : ScoreboardConnection.UPDATE;
        return new ScoreboardConnection.Change(team.getName(), mode, team.getPlayers());
    }

    @Override public boolean opensScoreboard(Object packet) { return packet instanceof ClientboundLoginPacket; }

    @Override public Object remove(String name) {
        return ClientboundSetPlayerTeamPacket.createRemovePacket(new PlayerTeam(new Scoreboard(), name));
    }

}
