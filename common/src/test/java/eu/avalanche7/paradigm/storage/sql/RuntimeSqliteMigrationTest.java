package eu.avalanche7.paradigm.storage.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.runtime.RuntimeJdbcDriverProvider;
import eu.avalanche7.paradigm.storage.runtime.RuntimeLibrary;
import eu.avalanche7.paradigm.storage.runtime.RuntimeLibraryManager;
import eu.avalanche7.paradigm.storage.sqlite.SqliteDialect;

class RuntimeSqliteMigrationTest {
    @TempDir Path directory;

    @Test
    void pinnedDriverMigratesLegacyPunishmentsDespiteACompetingDriver() throws Exception {
        var dialect = new SqliteDialect();
        Class<?> classpathDriver = Class.forName(dialect.driverClassName(), false, getClass().getClassLoader());
        Path jar = Path.of(classpathDriver.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.copy(jar, directory.resolve(RuntimeLibrary.SQLITE.fileName()));
        var config = new StorageConfig();
        config.sqlite.path = directory.resolve("legacy.db").toString();
        config.runtimeLibraries.cachePath = directory.toString();
        config.runtimeLibraries.enabled = true;
        config.runtimeLibraries.allowDownload = false;
        Driver competing = (Driver) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Driver.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "connect" -> throw new AssertionError("Competing driver selected");
                    case "toString" -> "Competing SQLite driver";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DriverManager.registerDriver(competing);
        try (var drivers = new RuntimeJdbcDriverProvider(new RuntimeLibraryManager(config, null, null), null);
             var connections = new SqlConnectionProvider(config, dialect, drivers)) {
            var runner = new SqlMigrationRunner(connections, null);
            var oldSchema = SqlMigrationRunner.loadMigrations(dialect.migrationsPath()).stream()
                    .filter(migration -> migration.version() <= 5).toList();
            assertEquals(5, runner.run(oldSchema).currentVersion());
            try (var connection = connections.getConnection(); var sql = connection.createStatement()) {
                assertNotSame(classpathDriver.getClassLoader(), connection.getClass().getClassLoader());
                assertEquals(RuntimeLibrary.SQLITE.version(), connection.getMetaData().getDriverVersion());
                sql.executeUpdate("INSERT INTO moderation_punishments(id, type, name, created_at_ms) VALUES (42, 'tempban', 'Alex', 100)");
                sql.executeUpdate("INSERT INTO moderation_warnings(id, name, created_at_ms) VALUES (42, 'Alex', 101)");
            }

            assertEquals(SqlMigrationRunner.latestAvailableVersion(dialect), runner.runAvailableMigrations().currentVersion());
            assertTrue(runner.runAvailableMigrations().appliedVersions().isEmpty());
            try (var connection = connections.getConnection(); var sql = connection.createStatement();
                 var rows = sql.executeQuery("SELECT punishment_id, punishment_type, subject_name FROM moderation_punishment_ledger ORDER BY punishment_id")) {
                assertTrue(rows.next());
                assertEquals("P-L-000000000000002A", rows.getString("punishment_id"));
                assertEquals("BAN", rows.getString("punishment_type"));
                assertEquals("Alex", rows.getString("subject_name"));
                assertTrue(rows.next());
                assertEquals("P-L-A00000000000002A", rows.getString("punishment_id"));
                assertEquals("WARN", rows.getString("punishment_type"));
                assertEquals("Alex", rows.getString("subject_name"));
                assertFalse(rows.next());
            }
        } finally {
            DriverManager.deregisterDriver(competing);
        }
    }
}
