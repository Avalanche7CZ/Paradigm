package eu.avalanche7.paradigm.storage.migration;

import java.util.Locale;

public record StorageMigrationOptions(
        boolean dryRun,
        ConflictPolicy conflictPolicy,
        boolean backupJsonBeforeMigration,
        String jsonBackupPath
) {
    public static StorageMigrationOptions defaults() {
        return new StorageMigrationOptions(false, ConflictPolicy.OVERWRITE, true, "");
    }

    public StorageMigrationOptions(boolean dryRun, ConflictPolicy conflictPolicy, boolean backupJsonBeforeMigration) {
        this(dryRun, conflictPolicy, backupJsonBeforeMigration, "");
    }

    public StorageMigrationOptions {
        if (conflictPolicy == null) {
            conflictPolicy = ConflictPolicy.OVERWRITE;
        }
        jsonBackupPath = jsonBackupPath != null ? jsonBackupPath : "";
    }

    public StorageMigrationOptions withJsonBackupPath(String path) {
        return new StorageMigrationOptions(dryRun, conflictPolicy, backupJsonBeforeMigration, path);
    }

    public enum ConflictPolicy {
        OVERWRITE,
        SKIP,
        FAIL;

        public static ConflictPolicy parse(String value) {
            String normalized = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
            return switch (normalized) {
                case "skip", "keep" -> SKIP;
                case "fail", "abort" -> FAIL;
                case "overwrite", "replace", "" -> OVERWRITE;
                default -> null;
            };
        }

        public String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
