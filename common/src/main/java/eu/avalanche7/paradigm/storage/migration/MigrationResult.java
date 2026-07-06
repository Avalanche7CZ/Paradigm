package eu.avalanche7.paradigm.storage.migration;

import java.util.List;

public record MigrationResult(
        boolean success,
        int currentVersion,
        List<Integer> appliedVersions,
        String message
) {
    public MigrationResult {
        appliedVersions = appliedVersions != null ? List.copyOf(appliedVersions) : List.of();
    }
}
