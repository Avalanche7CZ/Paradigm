package eu.avalanche7.paradigm.storage.migration;

import java.util.List;

public interface MigrationRunner {
    MigrationResult run(List<Migration> migrations);
}
