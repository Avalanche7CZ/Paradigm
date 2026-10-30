package eu.avalanche7.paradigm.modules.tab;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class TablistTeamOrder {
    private record Entry(String team, String subject, int position) {}
    private static final class Viewer {
        final Map<String, Set<String>> foreign = new HashMap<>();
        final Map<String, Entry> sent = new LinkedHashMap<>();
        final Set<String> dirty = new HashSet<>();
    }
    private final Map<String, Viewer> viewers = new HashMap<>();
    private final ThreadLocal<Boolean> sending = new ThreadLocal<>();
    private long sequence;

    public synchronized boolean apply(List<IPlayer> sorted, IPlatformAdapter platform, BiPredicate<IPlayer, IPlayer> visible) {
        Set<String> online = new HashSet<>();
        sorted.forEach(player -> online.add(player.getUUID()));
        viewers.keySet().retainAll(online);
        boolean applied = true;
        for (IPlayer viewer : sorted) {
            Viewer state = viewers.computeIfAbsent(viewer.getUUID(), ignored -> new Viewer());
            Map<String, Integer> desired = new LinkedHashMap<>();
            for (int index = 0; index < sorted.size(); index++) {
                IPlayer subject = sorted.get(index);
                if (!visible.test(viewer, subject)) continue;
                boolean foreign = platform.hasPlayerListTeam(subject) || state.foreign.values().stream().anyMatch(members -> members.contains(subject.getName()));
                if (foreign) { applied = false; continue; }
                desired.put(subject.getName(), index);
            }
            for (Entry entry : new ArrayList<>(state.sent.values())) {
                if (!desired.containsKey(entry.subject()) || desired.get(entry.subject()) != entry.position() || state.dirty.contains(entry.subject())) {
                    if (!state.foreign.containsKey(entry.team()) && !send(platform, viewer, entry.team(), entry.subject(), false)) { applied = false; continue; }
                    state.sent.remove(entry.subject());
                    state.dirty.remove(entry.subject());
                }
            }
            for (var target : desired.entrySet()) {
                if (state.sent.containsKey(target.getKey())) continue;
                String team;
                do { team = String.format(java.util.Locale.ROOT, "pg%08x%06x", target.getValue(), ++sequence & 0xffffffL); }
                while (state.foreign.containsKey(team) || owns(state, team));
                if (send(platform, viewer, team, target.getKey(), true)) state.sent.put(target.getKey(), new Entry(team, target.getKey(), target.getValue()));
                else applied = false;
            }
        }
        return applied;
    }

    private static boolean owns(Viewer state, String name) {
        return state.sent.values().stream().anyMatch(entry -> entry.team().equals(name));
    }

    public synchronized void observe(IPlatformAdapter platform, IPlayer viewer, String team, int mode, Collection<String> members) {
        if (Boolean.TRUE.equals(sending.get()) || mode == 2) return;
        Viewer state = viewers.computeIfAbsent(viewer.getUUID(), ignored -> new Viewer());
        Set<String> affected = new HashSet<>(state.foreign.getOrDefault(team, Set.of()));
        affected.addAll(members);
        affected.retainAll(state.sent.keySet());
        state.dirty.addAll(affected);
        for (Entry entry : new ArrayList<>(state.sent.values())) {
            if (entry.team().equals(team) && (mode == 0 || mode == 1)) {
                if (mode == 0) send(platform, viewer, entry.team(), entry.subject(), false);
                state.sent.remove(entry.subject());
            }
        }
        if (mode == 0 || mode == 3) {
            state.foreign.values().forEach(previous -> previous.removeAll(members));
            if (mode == 0) state.foreign.put(team, new HashSet<>(members));
            else state.foreign.computeIfAbsent(team, ignored -> new HashSet<>()).addAll(members);
        } else if (mode == 1) state.foreign.remove(team);
        else if (mode == 4 && state.foreign.containsKey(team)) state.foreign.get(team).removeAll(members);
    }

    public synchronized void disconnected(String uuid) { viewers.remove(uuid); }

    public synchronized void clear(IPlatformAdapter platform, List<IPlayer> players) {
        for (IPlayer player : players) {
            Viewer state = viewers.get(player.getUUID());
            if (state == null) continue;
            for (Entry entry : new ArrayList<>(state.sent.values())) {
                if (state.foreign.containsKey(entry.team()) || send(platform, player, entry.team(), entry.subject(), false)) {
                    state.sent.remove(entry.subject());
                    state.dirty.remove(entry.subject());
                }
            }
        }
    }

    private boolean send(IPlatformAdapter platform, IPlayer viewer, String team, String subject, boolean create) {
        sending.set(true);
        try { return platform.sendPlayerListOrderTeam(viewer, team, subject, create); }
        finally { sending.remove(); }
    }
}
