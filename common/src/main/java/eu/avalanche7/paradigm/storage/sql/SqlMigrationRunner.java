package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.migration.Migration;
import eu.avalanche7.paradigm.storage.migration.MigrationResult;
import eu.avalanche7.paradigm.storage.migration.MigrationRunner;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class SqlMigrationRunner implements MigrationRunner {
    private final SqlConnectionProvider connections;
    private final Logger logger;
    private int currentVersion;

    public SqlMigrationRunner(SqlConnectionProvider connections, Logger logger) {
        this.connections = connections;
        this.logger = logger;
    }

    public MigrationResult runAvailableMigrations() {
        return run(loadMigrations(connections.dialect().migrationsPath()));
    }

    @Override
    public MigrationResult run(List<Migration> migrations) {
        List<Integer> applied = new ArrayList<>();
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            ensureSchemaVersionTable(connection);
            Set<Integer> existing = appliedVersions(connection);
            for (Migration migration : migrations) {
                if (existing.contains(migration.version())) {
                    continue;
                }
                applyMigration(connection, migration);
                applied.add(migration.version());
                existing.add(migration.version());
                if (logger != null) {
                    logger.info("Paradigm storage: applied SQL migration V{} ({})", migration.version(), migration.resourcePath());
                }
            }
            connection.commit();
            currentVersion = existing.stream().mapToInt(Integer::intValue).max().orElse(0);
            return new MigrationResult(true, currentVersion, applied, "Migrations applied successfully.");
        } catch (Throwable t) {
            throw new StorageException("SQL migration failed: " + t.getMessage(), t);
        }
    }

    public int currentVersion() {
        return currentVersion;
    }

    private void ensureSchemaVersionTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS paradigm_schema_version (version INTEGER PRIMARY KEY, applied_at_ms BIGINT NOT NULL)");
        }
    }

    private Set<Integer> appliedVersions(Connection connection) throws Exception {
        Set<Integer> versions = new TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT version FROM paradigm_schema_version")) {
            while (rs.next()) {
                versions.add(rs.getInt(1));
            }
        }
        return versions;
    }

    private void applyMigration(Connection connection, Migration migration) throws Exception {
        for (String statementSql : splitStatements(migration.sql())) {
            if (statementSql.isBlank()) {
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
        try (var prepared = connection.prepareStatement("INSERT INTO paradigm_schema_version(version, applied_at_ms) VALUES(?, ?)")) {
            prepared.setInt(1, migration.version());
            prepared.setLong(2, System.currentTimeMillis());
            prepared.executeUpdate();
        }
    }

    private List<Migration> loadMigrations(String basePath) {
        List<Migration> migrations = new ArrayList<>();
        for (int version = 1; version <= 99; version++) {
            String resource = basePath + "/V" + version + "__" + switch (version) {
                case 1 -> "initial";
                case 2 -> "moderation";
                case 3 -> "permissions";
                default -> "migration";
            } + ".sql";
            String sql = resourceText(resource);
            if (sql == null) {
                if (version > 3) break;
                continue;
            }
            migrations.add(new Migration(version, resource, sql));
        }
        return migrations;
    }

    private String resourceText(String resource) {
        try (InputStream stream = SqlMigrationRunner.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw new StorageException("Could not read migration resource " + resource, t);
        }
    }

    private List<String> splitStatements(String sql) {
        String withoutComments = sql.replaceAll("(?m)^\\s*--.*$", "");
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        for (int i = 0; i < withoutComments.length(); i++) {
            char c = withoutComments.charAt(i);
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
            }
            if (c == ';' && !inSingleQuote) {
                statements.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().trim().isEmpty()) {
            statements.add(current.toString().trim());
        }
        return statements;
    }
}
