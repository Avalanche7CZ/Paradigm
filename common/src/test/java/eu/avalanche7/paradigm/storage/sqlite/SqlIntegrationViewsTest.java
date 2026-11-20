package eu.avalanche7.paradigm.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.sql.SqlMigrationRunner;

class SqlIntegrationViewsTest {
    private static final String PLAYER = "00000000-0000-0000-0000-000000000001";
    private static final String FALLBACK_PLAYER = "00000000-0000-0000-0000-000000000002";

    @TempDir
    Path tempDir;

    @Test
    void migratesAndExposesTheStableIntegrationViews() throws Exception {
        StorageConfig config = config("integration-views.db");
        SqliteStorageProvider provider = provider(config);
        assertEquals(SqlMigrationRunner.latestAvailableVersion(new SqliteDialect()), provider.migrationVersion());
        provider.close();

        try (Connection connection = connection(config)) {
            seed(connection);

            for (String view : List.of(
                    "paradigm_v_players",
                    "paradigm_v_player_groups",
                    "paradigm_v_active_player_groups",
                    "paradigm_v_group_members",
                    "paradigm_v_permission_tracks",
                    "paradigm_v_player_tracks",
                    "paradigm_v_player_punishment_summary",
                    "paradigm_v_player_ticket_summary",
                    "paradigm_v_servers",
                    "paradigm_v_player_home_summary",
                    "paradigm_v_warps_public",
                    "paradigm_v_punishments_public",
                    "paradigm_v_open_tickets")) {
                query(connection, "SELECT * FROM " + view + " LIMIT 1").close();
            }

            assertColumns(connection, "paradigm_v_players", "player_uuid", "player_name", "playtime_ms");
            assertColumns(connection, "paradigm_v_player_groups", "assignment_id", "status", "contexts_json", "context_hash");
            assertColumns(connection, "paradigm_v_player_tracks", "track_name", "position", "track_size", "expires_at_ms");
            assertColumns(connection, "paradigm_v_player_ticket_summary", "network_id", "open_ticket_count", "last_ticket_activity_at_ms");
            assertColumns(connection, "paradigm_v_warps_public", "world_id", "description", "updated_at_ms");
            assertFalse(columnsOf(connection, "paradigm_v_warps_public").contains("x"));
            assertFalse(columnsOf(connection, "paradigm_v_player_punishment_summary").contains("subject_ip_hash"));

            try (ResultSet result = query(connection, "SELECT status FROM paradigm_v_player_groups WHERE assignment_id = 'expired-at-boundary'")) {
                assertTrue(result.next());
                assertEquals("EXPIRED", result.getString("status"));
            }
            try (ResultSet result = query(connection, "SELECT COUNT(*) AS count FROM paradigm_v_active_player_groups WHERE assignment_id = 'expired-at-boundary'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt("count"));
            }
            try (ResultSet result = query(connection, "SELECT player_name FROM paradigm_v_active_player_groups WHERE assignment_id = 'fallback-name'")) {
                assertTrue(result.next());
                assertEquals("Permission Name", result.getString("player_name"));
            }
            try (ResultSet result = query(connection, "SELECT COUNT(*) AS count FROM paradigm_v_player_tracks WHERE player_uuid = '" + PLAYER + "' AND track_name = 'staff'")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt("count"));
            }
            try (ResultSet result = query(connection, "SELECT active_bans, active_mutes, active_jails, warning_count, active_punishment_count, total_punishment_count "
                    + "FROM paradigm_v_player_punishment_summary WHERE player_uuid = '" + PLAYER + "'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt("active_bans"));
                assertEquals(0, result.getInt("active_mutes"));
                assertEquals(0, result.getInt("active_jails"));
                assertEquals(1, result.getInt("warning_count"));
                assertEquals(2, result.getInt("active_punishment_count"));
                assertEquals(3, result.getInt("total_punishment_count"));
            }
            try (ResultSet result = query(connection, "SELECT open_ticket_count, resolved_ticket_count, closed_ticket_count, total_ticket_count "
                    + "FROM paradigm_v_player_ticket_summary WHERE network_id = 'test-network' AND player_uuid = '" + PLAYER + "'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt("open_ticket_count"));
                assertEquals(1, result.getInt("resolved_ticket_count"));
                assertEquals(1, result.getInt("closed_ticket_count"));
                assertEquals(3, result.getInt("total_ticket_count"));
            }
            try (ResultSet result = query(connection, "SELECT home_count FROM paradigm_v_player_home_summary WHERE player_uuid = '" + PLAYER + "' AND server_id = 'test-server'")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt("home_count"));
            }
        }

        SqliteStorageProvider reopened = provider(config);
        assertEquals(SqlMigrationRunner.latestAvailableVersion(new SqliteDialect()), reopened.migrationVersion());
        reopened.close();
        try (Connection connection = connection(config);
             ResultSet result = query(connection, "SELECT COUNT(*) AS count FROM paradigm_v_active_player_groups WHERE player_uuid = '" + PLAYER + "'")) {
            assertTrue(result.next());
            assertEquals(2, result.getInt("count"));
        }
    }

    private void seed(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO players(uuid, name, first_seen_ms, last_seen_ms, playtime_ms) VALUES('" + PLAYER + "', 'Profile Name', 1, 2, 3000)");
            statement.executeUpdate("INSERT INTO permission_users(uuid, name) VALUES('" + PLAYER + "', 'Permission Name')");
            statement.executeUpdate("INSERT INTO permission_users(uuid, name) VALUES('" + FALLBACK_PLAYER + "', 'Permission Name')");
            statement.executeUpdate("INSERT INTO permission_groups(name, description, prefix, suffix, weight) VALUES"
                    + "('admin', 'Administrator', '[A]', '', 100),"
                    + "('moderator', 'Moderator', '[M]', '', 50),"
                    + "('mythic', 'Premium', '[P]', '', 25)");
            statement.executeUpdate("INSERT INTO permission_user_groups(uuid, group_name, expires_at_ms, assigned_by, assigned_at_ms, contexts, context_hash, assignment_id) VALUES"
                    + "('" + PLAYER + "', 'admin', NULL, 'Console', 10, NULL, '', 'admin-permanent'),"
                    + "('" + PLAYER + "', 'moderator', 4102444800000, 'Console', 20, '{\"server\":\"test-server\"}', 'server=test-server', 'moderator-temporary'),"
                    + "('" + FALLBACK_PLAYER + "', 'mythic', NULL, 'Console', 30, '{\"network\":\"test-network\"}', 'network=test-network', 'fallback-name'),"
                    + "('" + PLAYER + "', 'mythic', CAST(ROUND((julianday('now') - 2440587.5) * 86400000) AS INTEGER), 'Console', 40, NULL, '', 'expired-at-boundary')");
            statement.executeUpdate("INSERT INTO permission_tracks(name) VALUES('staff'), ('donator')");
            statement.executeUpdate("INSERT INTO permission_track_members(track_name, group_name, position) VALUES"
                    + "('staff', 'admin', 1), ('staff', 'moderator', 2), ('donator', 'mythic', 1)");
            statement.executeUpdate("INSERT INTO moderation_punishment_ledger(punishment_id, punishment_type, scope, subject_uuid, subject_name, created_at_ms, starts_at_ms, expires_at_ms, updated_at_ms, metadata_json) VALUES"
                    + "('ban-active', 'BAN', 'GLOBAL', '" + PLAYER + "', 'Profile Name', 100, 100, NULL, 100, '{}'),"
                    + "('mute-expired', 'MUTE', 'GLOBAL', '" + PLAYER + "', 'Profile Name', 200, 200, CAST(ROUND((julianday('now') - 2440587.5) * 86400000) AS INTEGER), 200, '{}'),"
                    + "('warn-recorded', 'WARN', 'GLOBAL', '" + PLAYER + "', 'Profile Name', 300, 300, NULL, 300, '{}')");
            statement.executeUpdate("INSERT INTO tickets(ticket_id, ticket_key, network_id, origin_server_id, creator_uuid, creator_name, status, priority, created_at_ms, updated_at_ms, resolved_at_ms, closed_at_ms, last_activity_at_ms) VALUES"
                    + "('ticket-open', 'T-1', 'test-network', 'test-server', '" + PLAYER + "', 'Ticket Name', 'OPEN', 'NORMAL', 100, 100, NULL, NULL, 110),"
                    + "('ticket-resolved', 'T-2', 'test-network', 'test-server', '" + PLAYER + "', 'Ticket Name', 'RESOLVED', 'NORMAL', 200, 200, 200, NULL, 210),"
                    + "('ticket-closed', 'T-3', 'test-network', 'test-server', '" + PLAYER + "', 'Ticket Name', 'CLOSED', 'NORMAL', 300, 300, NULL, 300, 310)");
            statement.executeUpdate("INSERT INTO player_homes(server_id, uuid, home_name, world_id, x, y, z, yaw, pitch, created_at_ms, updated_at_ms) VALUES"
                    + "('test-server', '" + PLAYER + "', 'one', 'minecraft:overworld', 1, 2, 3, 0, 0, 1, 1),"
                    + "('test-server', '" + PLAYER + "', 'two', 'minecraft:overworld', 4, 5, 6, 0, 0, 1, 1)");
            statement.executeUpdate("INSERT INTO warps(server_id, name, world_id, x, y, z, yaw, pitch, permission, description, created_by, created_at_ms, updated_at_ms) VALUES"
                    + "('test-server', 'spawn', 'minecraft:overworld', 1, 2, 3, 0, 0, 'paradigm.warp.spawn', 'Spawn', 'Console', 1, 2)");
        }
    }

    private static void assertColumns(Connection connection, String view, String... expected) throws Exception {
        List<String> columns = columnsOf(connection, view);
        for (String column : expected) {
            assertTrue(columns.contains(column), () -> view + " is missing " + column);
        }
    }

    private static List<String> columnsOf(Connection connection, String view) throws Exception {
        try (ResultSet result = query(connection, "SELECT * FROM " + view + " LIMIT 1")) {
            List<String> columns = new ArrayList<>();
            for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
                columns.add(result.getMetaData().getColumnLabel(index).toLowerCase(java.util.Locale.ROOT));
            }
            return columns;
        }
    }

    private static ResultSet query(Connection connection, String sql) throws Exception {
        Statement statement = connection.createStatement();
        try {
            return statement.executeQuery(sql);
        } catch (Exception failure) {
            statement.close();
            throw failure;
        }
    }

    private SqliteStorageProvider provider(StorageConfig config) {
        ServerIdentityService identity = new ServerIdentityService(null, config);
        SqliteStorageProvider provider = new SqliteStorageProvider(config, new StorageContext(identity.current()), identity, null, null);
        provider.initialize();
        return provider;
    }

    private Connection connection(StorageConfig config) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + config.sqlite.path);
    }

    private StorageConfig config(String database) {
        StorageConfig config = new StorageConfig();
        config.provider = "sqlite";
        config.networkId = "test-network";
        config.serverId = "test-server";
        config.serverName = "Test Server";
        config.sqlite.path = tempDir.resolve(database).toString();
        config.runtimeLibraries.enabled = false;
        return config;
    }
}
