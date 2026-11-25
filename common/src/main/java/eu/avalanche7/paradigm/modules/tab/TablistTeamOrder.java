package eu.avalanche7.paradigm.modules.tab;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;

import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

/** Neutral sorting teams for clients without native player-list ordering. */
public final class TablistTeamOrder {
    public boolean apply(List<IPlayer> sorted, IPlatformAdapter platform, BiPredicate<IPlayer, IPlayer> visible) {
        boolean applied = true;
        for (IPlayer viewer : sorted) {
            var connection = platform.getScoreboardConnection(viewer);
            if (connection == null || !connection.view().ready()) {
                applied = false;
                continue;
            }
            var view = connection.view();
            Map<String, String> desired = new LinkedHashMap<>();
            for (int index = 0; index < sorted.size(); index++) {
                IPlayer subject = sorted.get(index);
                boolean canSee = visible.test(viewer, subject);
                connection.visibility(subject.getName(), canSee);
                if (!canSee) continue;
                String team = String.format(Locale.ROOT, "pg%08x", index);
                if (platform.hasPlayerListTeam(subject) || !view.allows(team, subject.getName())) {
                    applied = false;
                    continue;
                }
                desired.put(team, subject.getName());
            }
            for (var entry : view.owned().entrySet()) {
                if (!entry.getValue().subject().equals(desired.get(entry.getKey())) || !entry.getValue().containsSubject()) {
                    applied &= platform.sendPlayerListOrderTeam(viewer, entry.getKey(), entry.getValue().subject(), false);
                }
            }
            for (var entry : desired.entrySet()) {
                var present = connection.view().owned().get(entry.getKey());
                if (present != null && present.subject().equals(entry.getValue()) && present.containsSubject()) continue;
                applied &= platform.sendPlayerListOrderTeam(viewer, entry.getKey(), entry.getValue(), true);
            }
        }
        return applied;
    }

    public void clear(IPlatformAdapter platform, List<IPlayer> players) {
        for (IPlayer viewer : players) {
            var connection = platform.getScoreboardConnection(viewer);
            if (connection != null) connection.clearOwned();
        }
    }
}
