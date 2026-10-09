package eu.avalanche7.paradigm.platform.visual;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;

public final class LegacyTablistController {
    private final MinecraftServer server;
    private final LegacyVanishController vanish;
    private final Scoreboard scoreboard = new Scoreboard();
    private final Map<UUID, ScorePlayerTeam> teams = new HashMap<>();
    private final Map<UUID, Set<String>> sent = new HashMap<>();
    private long sequence;

    public LegacyTablistController(MinecraftServer server, LegacyVanishController vanish) { this.server = server; this.vanish = vanish; }

    public boolean display(EntityPlayerMP player, String text) {
        if (player == null) return false;
        if (text == null) { reset(player); return true; }
        NameParts parts = parts(player.getCommandSenderName(), text);
        if (parts == null || player.getTeam() != null) { reset(player); return false; }
        ScorePlayerTeam team = teams.get(player.getUniqueID());
        if (team == null) {
            String id;
            do { id = "pg" + Long.toString(++sequence, 36); }
            while (player.worldObj.getScoreboard().getTeam(id) != null);
            team = scoreboard.createTeam(id);
            scoreboard.func_151392_a(player.getCommandSenderName(), id);
            teams.put(player.getUniqueID(), team);
        } else if (team.getColorPrefix().equals(parts.prefix()) && team.getColorSuffix().equals(parts.suffix())) return true;
        team.setNamePrefix(parts.prefix());
        team.setNameSuffix(parts.suffix());
        for (EntityPlayerMP viewer : players()) send(viewer, team);
        return true;
    }

    public static NameParts parts(String name, String text) {
        int index = text.indexOf(name);
        if (index < 0 || text.indexOf(name, index + name.length()) >= 0 || text.indexOf('\n') >= 0) return null;
        return new NameParts(limit(text.substring(0, index)), limit(text.substring(index + name.length())));
    }

    static String limit(String text) {
        int end = Math.min(16, text.length());
        if (end > 0 && (text.charAt(end - 1) == '§' || Character.isHighSurrogate(text.charAt(end - 1)))) end--;
        return text.substring(0, end);
    }

    public void joined(EntityPlayerMP viewer) {
        sent.remove(viewer.getUniqueID());
        for (ScorePlayerTeam team : teams.values()) send(viewer, team);
    }

    public void visibilityChanged(EntityPlayerMP player) {
        ScorePlayerTeam team = teams.get(player.getUniqueID());
        if (team != null) for (EntityPlayerMP viewer : players()) send(viewer, team);
    }

    public void reset(EntityPlayerMP player) {
        ScorePlayerTeam team = teams.remove(player.getUniqueID());
        if (team == null) return;
        for (EntityPlayerMP viewer : players()) {
            Set<String> known = sent.get(viewer.getUniqueID());
            if (known != null && known.remove(team.getRegisteredName())) {
                viewer.playerNetServerHandler.sendPacket(new S3EPacketTeams(team, 1));
                if (player.getTeam() instanceof ScorePlayerTeam original) {
                    viewer.playerNetServerHandler.sendPacket(new S3EPacketTeams(original, List.of(player.getCommandSenderName()), 3));
                }
            }
        }
        scoreboard.removeTeam(team);
    }

    public void disconnected(EntityPlayerMP player) { reset(player); sent.remove(player.getUniqueID()); }

    public void tick() {
        for (EntityPlayerMP player : players()) {
            if (teams.containsKey(player.getUniqueID()) && player.getTeam() != null) reset(player);
        }
    }

    public void clear() {
        for (EntityPlayerMP player : players()) reset(player);
        teams.clear();
        sent.clear();
    }

    private void send(EntityPlayerMP viewer, ScorePlayerTeam team) {
        String subject = (String) team.getMembershipCollection().iterator().next();
        if (!vanish.listed(subject, viewer)) {
            Set<String> known = sent.get(viewer.getUniqueID());
            if (known != null && known.remove(team.getRegisteredName())) viewer.playerNetServerHandler.sendPacket(new S3EPacketTeams(team, 1));
            return;
        }
        boolean added = sent.computeIfAbsent(viewer.getUniqueID(), ignored -> new HashSet<>()).add(team.getRegisteredName());
        viewer.playerNetServerHandler.sendPacket(new S3EPacketTeams(team, added ? 0 : 2));
    }

    @SuppressWarnings("unchecked")
    private List<EntityPlayerMP> players() { return List.copyOf(server.getConfigurationManager().playerEntityList); }
    public record NameParts(String prefix, String suffix) {}
}
