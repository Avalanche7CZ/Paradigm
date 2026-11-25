package eu.avalanche7.paradigm.platform.visual;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;

import eu.avalanche7.paradigm.modules.tab.ScoreboardConnection;

public final class LegacyTablistController {
    private static final Pattern FORMAT_RUN = Pattern.compile("(?i)(?:§[0-9a-fk-or])+");

    private final MinecraftServer server;
    private final LegacyVanishController vanish;
    private final Scoreboard scoreboard = new Scoreboard();
    private final Map<NetworkManager, ScorePlayerTeam> teams = new HashMap<>();
    private final Map<ScoreboardConnection, List<String>> rebuilding = new java.util.WeakHashMap<>();
    private long sequence;

    public LegacyTablistController(MinecraftServer server, LegacyVanishController vanish) {
        this.server = server;
        this.vanish = vanish;
    }

    public synchronized boolean order(List<EntityPlayerMP> sorted) {
        boolean applied = true;
        for (EntityPlayerMP viewer : players()) {
            var connection = connection(viewer);
            if (connection == null || !connection.view().ready()) { applied = false; continue; }
            var visible = sorted.stream().filter(player -> vanish.listed(player.getCommandSenderName(), viewer)).toList();
            List<String> desired = visible.stream().map(EntityPlayerMP::getCommandSenderName).toList();
            var listed = connection.view().listed();
            var actual = listed.stream().filter(desired::contains).toList();
            if (actual.equals(desired)) { rebuilding.remove(connection); continue; }
            var eligible = new HashSet<>(listed);
            eligible.addAll(rebuilding.getOrDefault(connection, List.of()));
            if (!eligible.containsAll(desired)) { applied = false; continue; }
            rebuilding.put(connection, desired);
            for (EntityPlayerMP player : visible) {
                if (listed.contains(player.getCommandSenderName())) viewer.playerNetServerHandler.sendPacket(
                        new S38PacketPlayerListItem(player.getCommandSenderName(), false, player.ping));
            }
            for (EntityPlayerMP player : visible) viewer.playerNetServerHandler.sendPacket(
                    new S38PacketPlayerListItem(player.getCommandSenderName(), true, player.ping));
            applied &= connection.view().listed().stream().filter(desired::contains).toList().equals(desired);
        }
        return applied;
    }

    public synchronized void resetOrder() { order(players()); }

    public synchronized boolean display(EntityPlayerMP player, String text) {
        NetworkManager transport = transport(player);
        if (transport == null) return false;
        if (text == null) { reset(player); return true; }
        NameParts parts = parts(player.getCommandSenderName(), text);
        if (parts == null) { reset(player); return false; }
        ScorePlayerTeam team = teams.get(transport);
        if (team == null) {
            String id;
            do { id = "pg" + Long.toString(++sequence, 36); }
            while (player.worldObj.getScoreboard().getTeam(id) != null || foreignTeamName(id));
            team = scoreboard.createTeam(id);
            scoreboard.func_151392_a(player.getCommandSenderName(), id);
            teams.put(transport, team);
        }
        team.setNamePrefix(parts.prefix());
        team.setNameSuffix(parts.suffix());
        boolean applied = false;
        for (EntityPlayerMP viewer : players()) applied |= send(viewer, player, team);
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
        String body = text.substring(0, tail).replaceFirst("(?i)(?:§[0-9a-fk-or]|\\s)+$", "");
        String style = formatting(text);
        String trailingStyle = text.substring(tail);
        if (trailingStyle.isEmpty() && !formatting(body).equals(style)) trailingStyle = style;
        String separated = body + " " + trailingStyle;
        if (separated.length() <= 16) return separated;

        // Reserve the separator and final style before truncating rank text. Otherwise
        // a long prefix can lose both its space and the color/reset intended for the name.
        if (style.isEmpty()) style = "§r";
        int budget = 16 - style.length() - 1;
        if (FORMAT_RUN.matcher(body).replaceAll("").length() <= budget) {
            // Prefer a complete rank over decorations when its visible text fits.
            if (body.length() > budget) body = compact(body.replaceAll("(?i)§[k-o]", ""));
            while (body.length() > budget) {
                Matcher runs = FORMAT_RUN.matcher(body);
                int start = -1, end = -1;
                while (runs.find()) { start = runs.start(); end = runs.end(); }
                if (start < 0) break;
                body = body.substring(0, start) + body.substring(end);
            }
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
        for (EntityPlayerMP subject : players()) send(viewer, subject, teams.get(transport(subject)));
    }

    public synchronized void visibilityChanged(EntityPlayerMP subject) {
        ScorePlayerTeam team = teams.get(transport(subject));
        if (team != null) for (EntityPlayerMP viewer : players()) send(viewer, subject, team);
    }

    public synchronized void visibilityChanged(EntityPlayerMP viewer, EntityPlayerMP subject) {
        send(viewer, subject, teams.get(transport(subject)));
    }

    public synchronized void reset(EntityPlayerMP subject) {
        ScorePlayerTeam team = teams.remove(transport(subject));
        if (team == null) return;
        for (EntityPlayerMP viewer : players()) remove(viewer, subject, team);
        scoreboard.removeTeam(team);
    }

    public synchronized void disconnected(EntityPlayerMP player) {
        reset(player);
        rebuilding.remove(connection(player));
    }

    public synchronized void tick() {
        for (EntityPlayerMP subject : players()) {
            ScorePlayerTeam team = teams.get(transport(subject));
            if (team != null) for (EntityPlayerMP viewer : players()) send(viewer, subject, team);
        }
    }

    public synchronized void clear() {
        for (EntityPlayerMP viewer : players()) {
            var connection = connection(viewer);
            if (connection != null) connection.clearOwned();
        }
        for (ScorePlayerTeam team : teams.values()) scoreboard.removeTeam(team);
        rebuilding.clear();
        teams.clear();
    }

    private boolean send(EntityPlayerMP viewer, EntityPlayerMP player, ScorePlayerTeam team) {
        var connection = connection(viewer);
        if (team == null || connection == null || !connection.view().ready()) return false;
        String name = team.getRegisteredName();
        String subject = player.getCommandSenderName();
        var view = connection.view();
        if (!vanish.listed(subject, viewer) || player.getTeam() != null || !view.allows(name, subject)) {
            // A CREATE may still be in the transport queue. The journal validates this REMOVE when it reaches it.
            if (!view.foreignNames().contains(name)) remove(viewer, player, team);
            return false;
        }
        var appearance = new NameParts(team.getColorPrefix(), team.getColorSuffix());
        var present = view.owned().get(name);
        if (present == null) {
            connection.sendOwned(new S3EPacketTeams(team, ScoreboardConnection.CREATE), subject, appearance,
                    viewer.playerNetServerHandler::sendPacket);
        } else {
            if (!appearance.equals(present.appearance())) {
                connection.sendOwned(new S3EPacketTeams(team, ScoreboardConnection.UPDATE), subject, appearance,
                        viewer.playerNetServerHandler::sendPacket);
            }
            if (!present.containsSubject()) {
                connection.sendOwned(new S3EPacketTeams(team, List.of(subject), ScoreboardConnection.ADD), subject, appearance,
                        viewer.playerNetServerHandler::sendPacket);
            }
        }
        var applied = connection.view().owned().get(name);
        return applied != null && applied.containsSubject() && appearance.equals(applied.appearance());
    }

    private static void remove(EntityPlayerMP viewer, EntityPlayerMP subject, ScorePlayerTeam team) {
        var connection = connection(viewer);
        if (connection != null) connection.sendOwned(new S3EPacketTeams(team, ScoreboardConnection.REMOVE),
                subject.getCommandSenderName(), "", viewer.playerNetServerHandler::sendPacket);
    }

    private boolean foreignTeamName(String name) {
        for (EntityPlayerMP viewer : players()) {
            var connection = connection(viewer);
            if (connection != null && connection.view().foreignNames().contains(name)) return true;
        }
        return false;
    }

    public static ScoreboardConnection connection(EntityPlayerMP player) {
        return ScoreboardConnection.of(transport(player));
    }

    private static NetworkManager transport(EntityPlayerMP player) {
        return player == null || player.playerNetServerHandler == null ? null : player.playerNetServerHandler.netManager;
    }

    @SuppressWarnings("unchecked")
    private List<EntityPlayerMP> players() { return List.copyOf(server.getConfigurationManager().playerEntityList); }
    public record NameParts(String prefix, String suffix) {}
}
