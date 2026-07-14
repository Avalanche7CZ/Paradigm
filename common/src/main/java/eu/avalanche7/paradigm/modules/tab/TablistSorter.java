package eu.avalanche7.paradigm.modules.tab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TablistSorter {
    private TablistSorter() {
    }

    public static List<TablistEntry> sort(List<TablistEntry> entries, List<String> configuredRules) {
        List<TablistSortRule> rules = configuredRules == null ? List.of() : configuredRules.stream()
                .map(TablistSortRule::parse)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (rules.isEmpty()) rules = List.of(TablistSortRule.GROUP_WEIGHT_DESC, TablistSortRule.PLAYER_NAME_ASC);

        Comparator<TablistEntry> comparator = null;
        for (TablistSortRule rule : rules) {
            Comparator<TablistEntry> next = comparator(rule);
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        comparator = comparator
                .thenComparing(TablistEntry::playerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TablistEntry::uuid, Comparator.nullsLast(String::compareTo));

        List<TablistEntry> sorted = new ArrayList<>(entries != null ? entries : List.of());
        sorted.sort(comparator);
        return List.copyOf(sorted);
    }

    private static Comparator<TablistEntry> comparator(TablistSortRule rule) {
        return switch (rule) {
            case GROUP_WEIGHT_DESC -> Comparator.comparingInt((TablistEntry value) -> value.metadata().groupWeight()).reversed();
            case GROUP_WEIGHT_ASC -> Comparator.comparingInt(value -> value.metadata().groupWeight());
            case PLAYER_NAME_ASC -> Comparator.comparing(TablistEntry::playerName, String.CASE_INSENSITIVE_ORDER);
            case PLAYER_NAME_DESC -> Comparator.comparing(TablistEntry::playerName, String.CASE_INSENSITIVE_ORDER).reversed();
            case PING_ASC -> Comparator.comparingInt(TablistEntry::ping);
            case PING_DESC -> Comparator.comparingInt(TablistEntry::ping).reversed();
        };
    }
}
