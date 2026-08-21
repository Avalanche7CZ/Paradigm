package eu.avalanche7.paradigm.modules.tickets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TicketSummaries {

    public static final String TOTAL = "total";
    public static final String ACTIVE = "active";
    public static final String UNASSIGNED = "unassigned";
    public static final String ELEVATED = "elevated";

    private static final List<String> KEYS = List.of(
            TOTAL, ACTIVE, UNASSIGNED, ELEVATED,
            "open", "in_progress", "waiting_player", "waiting_staff", "resolved", "closed");

    private TicketSummaries() {
    }

    public static void accumulate(Map<String, Integer> counts, TicketStatus status, TicketPriority priority, boolean unassigned) {
        bump(counts, TOTAL);
        bump(counts, status.id());
        if (status.isActive()) {
            bump(counts, ACTIVE);
            if (unassigned) {
                bump(counts, UNASSIGNED);
            }
            if (priority.isElevated()) {
                bump(counts, ELEVATED);
            }
        }
    }

    public static Map<String, Integer> finish(Map<String, Integer> counts) {
        Map<String, Integer> complete = new LinkedHashMap<>();
        for (String key : KEYS) {
            complete.put(key, counts.getOrDefault(key, 0));
        }
        return complete;
    }

    public static Map<String, Integer> empty() {
        return finish(new LinkedHashMap<>());
    }

    private static void bump(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }
}
