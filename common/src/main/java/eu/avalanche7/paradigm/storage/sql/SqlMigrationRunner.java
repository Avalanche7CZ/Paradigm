package eu.avalanche7.paradigm.storage.sql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.migration.Migration;
import eu.avalanche7.paradigm.storage.migration.MigrationResult;
import eu.avalanche7.paradigm.storage.migration.MigrationRunner;

public class SqlMigrationRunner implements MigrationRunner {
    private static final Pattern ADD_COLUMN = Pattern.compile("(?i)^ALTER\\s+TABLE\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+ADD\\s+COLUMN\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+(.+)$");
    private static final Pattern CREATE_INDEX = Pattern.compile("(?i)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+ON\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s*\\(([^)]+)\\)$");
    private static final Pattern DROP_PRIMARY_KEY = Pattern.compile("(?i)^ALTER\\s+TABLE\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+DROP\\s+PRIMARY\\s+KEY$");
    private static final Pattern VARCHAR_SIZE = Pattern.compile("(?i)\\bVARCHAR\\s*\\(\\s*(\\d+)\\s*\\)");

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
        var operationLock = connections.operationLock();
        if (operationLock != null) {
            operationLock.lock();
        }
        List<Integer> applied = new ArrayList<>();
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            ensureSchemaVersionTable(connection);
            connection.commit();

            Set<Integer> existing = appliedVersions(connection);
            for (Migration migration : migrations) {
                if (existing.contains(migration.version())) {
                    continue;
                }
                try {
                    applyMigration(connection, migration);
                    connection.commit();
                } catch (Exception failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                    throw failure;
                }
                applied.add(migration.version());
                existing.add(migration.version());
                if (logger != null) {
                    logger.info("Paradigm storage: applied SQL migration V{} ({}).", migration.version(), migration.resourcePath());
                }
            }
            currentVersion = existing.stream().mapToInt(Integer::intValue).max().orElse(0);
            return new MigrationResult(true, currentVersion, applied, "Migrations applied successfully.");
        } catch (Exception t) {
            throw new StorageException("SQL migration failed: " + t.getMessage(), t);
        } finally {
            if (operationLock != null) {
                operationLock.unlock();
            }
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
            if (alreadyAppliedDdl(connection, statementSql)) {
                if (logger != null) {
                    logger.info("Paradigm storage: migration V{} resumed past an already-applied schema step.", migration.version());
                }
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

    private boolean alreadyAppliedDdl(Connection connection, String sql) throws SQLException {
        String normalized = sql.trim().replaceAll("\\s+", " ");

        Matcher addColumn = ADD_COLUMN.matcher(normalized);
        if (addColumn.matches()) {
            ColumnInfo column = findColumn(connection, addColumn.group(1), addColumn.group(2));
            if (column == null) {
                return false;
            }
            verifyColumnDefinition(addColumn.group(1), addColumn.group(2), addColumn.group(3), column);
            return true;
        }

        Matcher createIndex = CREATE_INDEX.matcher(normalized);
        if (createIndex.matches()) {
            List<String> expectedColumns = parseColumns(createIndex.group(4));
            IndexInfo index = findIndex(connection, createIndex.group(3), createIndex.group(2));
            if (index == null) {
                return false;
            }
            boolean expectedUnique = createIndex.group(1) != null;
            if (index.unique() != expectedUnique || !index.columns().equals(expectedColumns)) {
                throw new SQLException("Existing index " + createIndex.group(2) + " does not match the migration definition.");
            }
            return true;
        }

        Matcher dropPrimaryKey = DROP_PRIMARY_KEY.matcher(normalized);
        if (dropPrimaryKey.matches()) {
            return !hasPrimaryKey(connection, dropPrimaryKey.group(1));
        }

        return false;
    }

    private ColumnInfo findColumn(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String tableName : identifierCandidates(table)) {
            try (ResultSet rs = meta.getColumns(connection.getCatalog(), null, tableName, null)) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    if (name != null && name.equalsIgnoreCase(column)) {
                        return new ColumnInfo(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"));
                    }
                }
            }
        }
        return null;
    }

    private IndexInfo findIndex(Connection connection, String table, String indexName) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String tableName : identifierCandidates(table)) {
            List<IndexColumn> columns = new ArrayList<>();
            Boolean unique = null;
            try (ResultSet rs = meta.getIndexInfo(connection.getCatalog(), null, tableName, false, false)) {
                while (rs.next()) {
                    String currentName = rs.getString("INDEX_NAME");
                    if (currentName == null || !currentName.equalsIgnoreCase(indexName)) {
                        continue;
                    }
                    if (unique == null) {
                        unique = !rs.getBoolean("NON_UNIQUE");
                    }
                    String column = rs.getString("COLUMN_NAME");
                    if (column != null) {
                        columns.add(new IndexColumn(rs.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT)));
                    }
                }
            }
            if (unique != null) {
                columns.sort(java.util.Comparator.comparingInt(IndexColumn::position));
                return new IndexInfo(unique, columns.stream().map(IndexColumn::name).toList());
            }
        }
        return null;
    }

    private boolean hasPrimaryKey(Connection connection, String table) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String tableName : identifierCandidates(table)) {
            try (ResultSet rs = meta.getPrimaryKeys(connection.getCatalog(), null, tableName)) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void verifyColumnDefinition(String table, String column, String definition, ColumnInfo actual) throws SQLException {
        String expected = definition.toUpperCase(Locale.ROOT);
        String actualType = actual.typeName() != null ? actual.typeName().toUpperCase(Locale.ROOT) : "";

        Matcher varchar = VARCHAR_SIZE.matcher(expected);
        if (varchar.find()) {
            int expectedSize = Integer.parseInt(varchar.group(1));
            if (!actualType.contains("CHAR") || actual.size() != expectedSize) {
                throw new SQLException("Existing column " + table + "." + column + " does not match expected VARCHAR(" + expectedSize + ").");
            }
            return;
        }

        if (expected.matches(".*\\bTEXT\\b.*") && !(actualType.contains("TEXT") || actualType.contains("CLOB") || actualType.contains("CHAR"))) {
            throw new SQLException("Existing column " + table + "." + column + " does not match expected TEXT type.");
        }
    }

    private List<String> parseColumns(String raw) {
        List<String> result = new ArrayList<>();
        for (String value : raw.split(",")) {
            String column = value.trim().replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
            if (!column.isBlank()) {
                result.add(column);
            }
        }
        return List.copyOf(result);
    }

    private List<String> identifierCandidates(String identifier) {
        return List.of(identifier, identifier.toLowerCase(Locale.ROOT), identifier.toUpperCase(Locale.ROOT));
    }

    private List<Migration> loadMigrations(String basePath) {
        List<Migration> migrations = new ArrayList<>();
        for (int version = 1; version <= 99; version++) {
            String resource = basePath + "/V" + version + "__" + switch (version) {
                case 1 -> "initial";
                case 2 -> "moderation";
                case 3 -> "permissions";
                case 4 -> "audit";
                case 5 -> "permission_assignments";
                case 6 -> "punishment_ledger";
                case 7 -> "managed_config";
                case 8 -> "server_instances_metadata";
                case 9 -> "permission_assignment_keys";
                case 10 -> "player_playtime";
                case 11 -> "tickets";
                default -> "migration";
            } + ".sql";
            String sql = resourceText(resource);
            if (sql == null) {
                if (version > 4) break;
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
        } catch (IOException t) {
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

    private record ColumnInfo(String typeName, int size) {
    }

    private record IndexColumn(int position, String name) {
    }

    private record IndexInfo(boolean unique, List<String> columns) {
    }
}
