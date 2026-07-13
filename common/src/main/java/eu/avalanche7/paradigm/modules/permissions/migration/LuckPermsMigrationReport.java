package eu.avalanche7.paradigm.modules.permissions.migration;

import java.util.List;

public record LuckPermsMigrationReport(
        boolean ok,
        boolean dryRun,
        int groups,
        int users,
        int permissions,
        int memberships,
        int parents,
        int metadata,
        int conflicts,
        int skipped,
        List<String> details
) {
    public LuckPermsMigrationReport {
        details = List.copyOf(details);
    }
}
