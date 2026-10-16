package eu.avalanche7.paradigm.platform.visual;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;

public final class LegacyTablistController {
    private static final Pattern FORMAT_RUN = Pattern.compile("(?i)(?:§[0-9a-fk-or])+");

    private final MinecraftServer server;
    private final LegacyVanishController vanish;
    private final Scoreboard scoreboard = new Scoreboard();
    private final Map<UUID, ScorePlayerTeam> teams = new HashMap<>();
    private final Map<UUID, Set<String>> sent = new HashMap<>();
    private final Map<UUID, Map<String, Set<String>>> foreignTeams = new HashMap<>();
    private final Map<UUID, Set<String>> dirtyMembers = new HashMap<>();
    private final ThreadLocal<Boolean> sending = new ThreadLocal<>();
    private long sequence;

    public LegacyTablistController(MinecraftServer server, LegacyVanishController vanish) { this.server = server; this.vanish = vanish; }

    public synchronized boolean display(EntityPlayerMP player, String text) {
        if (player == null) return false;
        if (text == null) { reset(player); return true; }
        NameParts parts = parts(player.getCommandSenderName(), text);
        if (parts == null) { reset(player); return false; }
        ScorePlayerTeam team = teams.get(player.getUniqueID());
        boolean changed = team == null || !team.getColorPrefix().equals(parts.prefix()) || !team.getColorSuffix().equals(parts.suffix());
        if (team == null) {
            String id;
            do { id = "pg" + Long.toString(++sequence, 36); }
            while (player.worldObj.getScoreboard().getTeam(id) != null || foreignTeamName(id));
            team = scoreboard.createTeam(id);
            scoreboard.func_151392_a(player.getCommandSenderName(), id);
            teams.put(player.getUniqueID(), team);
        }
        team.setNamePrefix(parts.prefix());
        team.setNameSuffix(parts.suffix());
        boolean applied = false;
        for (EntityPlayerMP viewer : players()) applied |= send(viewer, player, team, changed);
        return applied;
    }

    public static NameParts parts(String name, String text) {
        int index = text.indexOf(name);
        if (name.isEmpty() || index < 0 || text.indexOf(name, index + name.length()) >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) return null;
        return new NameParts(prefix(text.substring(0, index)), limit(compact(text.substring(index + name.length()))));
    }

    private static String prefix(String text) {
        text = compact(text);
        // A leading reset is redundant on the client's fresh name draw.
        if (text.startsWith("§r")) text = text.substring(2);
        String visible = FORMAT_RUN.matcher(text).replaceAll("");
        if (visible.isEmpty()) return limit(text);
        int tail = text.length();
        while (tail >= 2 && text.charAt(tail - 2) == '§') tail -= 2;
        String separator = Character.isWhitespace(visible.charAt(visible.length() - 1)) ? "" : " ";
        String separated = text.substring(0, tail) + separator + text.substring(tail);
        if (separated.length() <= 16) return separated;

        // Reserve the separator and final style before truncating rank text. Otherwise
        // a long prefix can lose both its space and the color/reset intended for the name.
        String style = formatting(text);
        if (style.isEmpty()) style = "§r";
        int budget = 16 - style.length() - 1;
        String body = text.substring(0, tail).stripTrailing();
        if (FORMAT_RUN.matcher(body).replaceAll("").length() <= budget) {
            // Prefer a complete rank over decorations when its visible text fits.
            if (body.length() > budget) body = compact(body.replaceAll("(?i)§[k-o]", ""));
            while (body.length() > budget) body = FORMAT_RUN.matcher(body).replaceFirst("");
        }
        return limit(body, budget).stripTrailing() + " " + style;
    }

    private static String compact(String text) {
        Matcher runs = FORMAT_RUN.matcher(text);
        StringBuilder result = new StringBuilder();
        String previous = "";
        while (runs.find()) {
            String next = formatting(previous + runs.group());
            String change = next.startsWith(previous) ? next.substring(previous.length()) : next;
            runs.appendReplacement(result, Matcher.quoteReplacement(change));
            previous = next;
        }
        runs.appendTail(result);
        return result.toString();
    }

    private static String formatting(String text) {
        String color = "";
        StringBuilder styles = new StringBuilder();
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != '§') continue;
            char code = Character.toLowerCase(text.charAt(++i));
            if ("0123456789abcdefr".indexOf(code) >= 0) {
                color = "§" + code;
                styles.setLength(0);
            } else if ("klmno".indexOf(code) >= 0 && styles.indexOf("§" + code) < 0) {
                styles.append('§').append(code);
            }
        }
        return color + styles;
    }

    static String limit(String text) { return limit(text, 16); }

    private static String limit(String text, int length) {
        int end = Math.min(length, text.length());
        if (end > 0 && (text.charAt(end - 1) == '§' || Character.isHighSurrogate(text.charAt(end - 1)))) end--;
        return text.substring(0, end);
    }

    public synchronized void joined(EntityPlayerMP viewer) {
        sent.remove(viewer.getUniqueID());
        for (EntityPlayerMP player : players()) {
            ScorePlayerTeam team = teams.get(player.getUniqueID());
            if (team != null) send(viewer, player, team, false);
        }
    }

    public synchronized void visibilityChanged(EntityPlayerMP player) {
        ScorePlayerTeam team = teams.get(player.getUniqueID());
        if (team != null) for (EntityPlayerMP viewer : players()) send(viewer, player, team, false);
    }

    public synchronized void visibilityChanged(EntityPlayerMP viewer, EntityPlayerMP player) {
        ScorePlayerTeam team = teams.get(player.getUniqueID());
        if (team != null) send(viewer, player, team, false);
    }

    public synchronized void reset(EntityPlayerMP player) {
        ScorePlayerTeam team = teams.remove(player.getUniqueID());
        if (team == null) return;
        for (EntityPlayerMP viewer : players()) {
            Set<String> known = sent.get(viewer.getUniqueID());
            if (known != null && known.remove(team.getRegisteredName())) {
                if (!foreignTeams.getOrDefault(viewer.getUniqueID(), Map.of()).containsKey(team.getRegisteredName()))
                    packet(viewer, new S3EPacketTeams(team, 1));
            }
            Set<String> dirty = dirtyMembers.get(viewer.getUniqueID());
            if (dirty != null) dirty.remove(player.getCommandSenderName());
        }
        scoreboard.removeTeam(team);
    }

    public synchronized void disconnected(EntityPlayerMP player) {
        reset(player);
        sent.remove(player.getUniqueID());
        foreignTeams.remove(player.getUniqueID());
        dirtyMembers.remove(player.getUniqueID());
    }

    public synchronized void tick() {
        for (EntityPlayerMP player : players()) {
            ScorePlayerTeam team = teams.get(player.getUniqueID());
            if (team != null) for (EntityPlayerMP viewer : players()) send(viewer, player, team, false);
        }
    }

    public synchronized void clear() {
        for (EntityPlayerMP player : players()) reset(player);
        teams.clear();
        sent.clear();
        foreignTeams.clear();
        dirtyMembers.clear();
    }

    private boolean send(EntityPlayerMP viewer, EntityPlayerMP player, ScorePlayerTeam team, boolean changed) {
        String subject = player.getCommandSenderName();
        Map<String, Set<String>> foreign = foreignTeams.getOrDefault(viewer.getUniqueID(), Map.of());
        Set<String> known = sent.computeIfAbsent(viewer.getUniqueID(), ignored -> new HashSet<>());
        Set<String> dirty = dirtyMembers.get(viewer.getUniqueID());
        boolean membershipChanged = dirty != null && dirty.remove(subject);
        if (!vanish.listed(subject, viewer) || player.getTeam() != null
                || foreign.containsKey(team.getRegisteredName())
                || foreign.values().stream().anyMatch(members -> members.contains(subject))) {
            if (known.remove(team.getRegisteredName()) && !foreign.containsKey(team.getRegisteredName()))
                packet(viewer, new S3EPacketTeams(team, 1));
            return false;
        }
        if (!known.contains(team.getRegisteredName())) {
            packet(viewer, new S3EPacketTeams(team, 0));
            known.add(team.getRegisteredName());
        } else {
            if (changed) packet(viewer, new S3EPacketTeams(team, 2));
            if (membershipChanged) packet(viewer, new S3EPacketTeams(team, List.of(subject), 3));
        }
        return true;
    }

    /** Observe outbound teams, including per-viewer Bukkit and packet-only plugin teams. */
    public synchronized void packetSent(EntityPlayerMP viewer, String name, int mode, Collection<String> members) {
        if (Boolean.TRUE.equals(sending.get()) || mode == 2) return;
        Set<String> known = sent.get(viewer.getUniqueID());
        if ((mode == 0 || mode == 1) && known != null && known.remove(name) && mode == 0) {
            // Make room before a plugin creates the same id: duplicate team creation crashes 1.7.10 clients.
            ScorePlayerTeam own = scoreboard.getTeam(name);
            if (own != null) packet(viewer, new S3EPacketTeams(own, 1));
        }
        Map<String, Set<String>> foreign = foreignTeams.computeIfAbsent(viewer.getUniqueID(), ignored -> new HashMap<>());
        Set<String> affected = new HashSet<>(foreign.getOrDefault(name, Set.of()));
        affected.addAll(members);
        if (mode == 0 || mode == 3) {
            for (Set<String> previous : foreign.values()) previous.removeAll(members);
            if (mode == 0) foreign.put(name, new HashSet<>(members));
            else foreign.computeIfAbsent(name, ignored -> new HashSet<>()).addAll(members);
        } else if (mode == 1) foreign.remove(name);
        else if (mode == 4 && foreign.containsKey(name)) foreign.get(name).removeAll(members);

        for (ScorePlayerTeam own : teams.values()) {
            for (Object member : own.getMembershipCollection()) {
                if (affected.contains(member)) dirtyMembers.computeIfAbsent(viewer.getUniqueID(), ignored -> new HashSet<>()).add((String) member);
            }
        }
    }

    private void packet(EntityPlayerMP viewer, S3EPacketTeams packet) {
        sending.set(true);
        try {
            viewer.playerNetServerHandler.sendPacket(packet);
        } finally {
            sending.remove();
        }
    }

    private boolean foreignTeamName(String name) {
        for (Map<String, Set<String>> board : foreignTeams.values()) if (board.containsKey(name)) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<EntityPlayerMP> players() { return List.copyOf(server.getConfigurationManager().playerEntityList); }
    public record NameParts(String prefix, String suffix) {}
}
