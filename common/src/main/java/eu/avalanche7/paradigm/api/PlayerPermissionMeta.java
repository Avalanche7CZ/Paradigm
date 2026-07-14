package eu.avalanche7.paradigm.api;

import java.util.List;

/** Read-only resolved permission metadata suitable for display. */
public record PlayerPermissionMeta(String primaryGroup, String prefix, String suffix, List<String> resolvedGroups) {
    public static final PlayerPermissionMeta EMPTY = new PlayerPermissionMeta("", "", "", List.of());

    public PlayerPermissionMeta {
        primaryGroup = primaryGroup != null ? primaryGroup : "";
        prefix = prefix != null ? prefix : "";
        suffix = suffix != null ? suffix : "";
        resolvedGroups = resolvedGroups != null ? List.copyOf(resolvedGroups) : List.of();
    }
}
