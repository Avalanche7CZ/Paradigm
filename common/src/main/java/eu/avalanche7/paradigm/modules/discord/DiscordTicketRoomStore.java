package eu.avalanche7.paradigm.modules.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import eu.avalanche7.paradigm.modules.tickets.SqlTicketRepository;
import eu.avalanche7.paradigm.modules.tickets.Ticket;
import eu.avalanche7.paradigm.storage.sql.SqlExecutor;

/** Durable provisioning and access state for guild rooms. The ticket itself stays in TicketService storage. */
public final class DiscordTicketRoomStore {
    private static final int BATCH_SIZE = 12;
    private final SqlExecutor sql;
    private final String network;
    private final SqlTicketRepository tickets;

    public DiscordTicketRoomStore(SqlExecutor sql, String network, SqlTicketRepository tickets) {
        this.sql = sql;
        this.network = network;
        this.tickets = tickets;
    }

    record Room(String ticketId, String channelId, String marker, String phase, boolean openingPending, String possibleUser) {}

    Optional<Ticket> ticket(String ticketId) {
        return tickets.findById(network, ticketId);
    }

    void discover(String guild, long enabledAt) {
        sql.update(insert("discord_ticket_room_activation", "network_id, guild_id, since_ms", "?, ?, ?",
                "network_id, guild_id"), stmt -> {
            stmt.setString(1, network);
            stmt.setString(2, guild);
            stmt.setLong(3, enabledAt);
        });
        List<String> ids = sql.query("""
                SELECT t.ticket_id FROM tickets t
                JOIN discord_ticket_room_activation a ON a.network_id = t.network_id AND a.guild_id = ?
                WHERE t.network_id = ? AND t.created_at_ms > a.since_ms
                    AND NOT EXISTS (SELECT 1 FROM discord_ticket_rooms r WHERE r.network_id = t.network_id AND r.ticket_id = t.ticket_id)
                    AND EXISTS (SELECT 1 FROM ticket_events e WHERE e.ticket_id = t.ticket_id
                        AND e.network_id = t.network_id AND e.event_type = 'CREATED')
                ORDER BY t.created_at_ms, t.ticket_id LIMIT ?
                """, stmt -> {
            stmt.setString(1, guild);
            stmt.setString(2, network);
            stmt.setInt(3, BATCH_SIZE);
        }, rs -> {
            var result = new ArrayList<String>();
            while (rs.next()) {
                result.add(rs.getString(1));
            }
            return result;
        });
        for (String id : ids) {
            sql.update(insert("discord_ticket_rooms", "network_id, ticket_id, guild_id, marker, phase", "?, ?, ?, ?, 'PENDING'",
                    "network_id, ticket_id"), stmt -> {
                stmt.setString(1, network);
                stmt.setString(2, id);
                stmt.setString(3, guild);
                stmt.setString(4, UUID.randomUUID().toString());
            });
        }
    }

    boolean hasState(String guild) {
        return sql.query("""
                SELECT guild_id FROM discord_ticket_room_activation WHERE network_id = ? AND guild_id = ?
                UNION ALL SELECT guild_id FROM discord_ticket_rooms
                    WHERE network_id = ? AND guild_id = ? AND phase IN ('CREATING', 'BOUND')
                LIMIT 1
                """, stmt -> {
            stmt.setString(1, network);
            stmt.setString(2, guild);
            stmt.setString(3, network);
            stmt.setString(4, guild);
        }, rs -> rs.next());
    }

    void advanceFloor(String guild, long since) {
        sql.update("""
                UPDATE discord_ticket_room_activation SET since_ms = ?
                WHERE network_id = ? AND guild_id = ? AND since_ms < ?
                """, stmt -> {
            stmt.setLong(1, since);
            stmt.setString(2, network);
            stmt.setString(3, guild);
            stmt.setLong(4, since);
        });
    }

    void pause(String guild) {
        sql.update("DELETE FROM discord_ticket_room_activation WHERE network_id = ? AND guild_id = ?", stmt -> {
            stmt.setString(1, network);
            stmt.setString(2, guild);
        });
        // Already-issued remote requests still need reconciliation. Unsubmitted rooms need no channel.
        sql.update("UPDATE discord_ticket_rooms SET phase = 'SKIPPED' WHERE network_id = ? AND guild_id = ? AND phase = 'PENDING'", stmt -> {
            stmt.setString(1, network);
            stmt.setString(2, guild);
        });
    }

    List<Room> work(String guild, String roles, long now) {
        return sql.query("""
                SELECT r.ticket_id, r.channel_id, r.marker, r.phase, r.opening_pending, r.applied_user
                FROM discord_ticket_rooms r
                JOIN tickets t ON t.ticket_id = r.ticket_id AND t.network_id = r.network_id
                LEFT JOIN discord_account_links l ON l.network_id = r.network_id AND l.player_uuid = LOWER(t.creator_uuid)
                WHERE r.network_id = ? AND r.guild_id = ? AND r.retry_at_ms <= ?
                    AND (r.phase IN ('PENDING', 'CREATING')
                    OR (r.phase = 'BOUND' AND (r.opening_pending = 1
                        OR COALESCE(r.applied_user, '') <> COALESCE(l.discord_id, '')
                        OR COALESCE(r.applied_roles, '') <> ?
                        OR COALESCE(r.applied_writable, -1) <> CASE WHEN t.status IN ('CLOSED', 'RESOLVED') THEN 0 ELSE 1 END)))
                ORDER BY CASE WHEN r.phase = 'BOUND' THEN 0 ELSE 1 END, r.retry_at_ms, t.created_at_ms, t.ticket_id LIMIT ?
                """, stmt -> {
            stmt.setString(1, network);
            stmt.setString(2, guild);
            stmt.setLong(3, now);
            stmt.setString(4, roles);
            stmt.setInt(5, BATCH_SIZE);
        }, rs -> {
            var result = new ArrayList<Room>();
            while (rs.next()) {
                result.add(new Room(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5) == 1, rs.getString(6)));
            }
            return result;
        });
    }

    boolean beginCreate(String ticket, String user) {
        return sql.update("""
                UPDATE discord_ticket_rooms SET phase = 'CREATING', applied_user = ?
                WHERE network_id = ? AND ticket_id = ? AND phase = 'PENDING'
                """, stmt -> {
            stmt.setString(1, user);
            stmt.setString(2, network);
            stmt.setString(3, ticket);
        }) == 1;
    }

    void recordPossibleAccess(String ticket, String user) {
        sql.update("""
                UPDATE discord_ticket_rooms SET applied_user = ?, applied_writable = NULL
                WHERE network_id = ? AND ticket_id = ? AND phase = 'BOUND'
                """, stmt -> {
            stmt.setString(1, user);
            stmt.setString(2, network);
            stmt.setString(3, ticket);
        });
    }

    boolean changePhase(String ticket, String before, String after) {
        return sql.update("UPDATE discord_ticket_rooms SET phase = ? WHERE network_id = ? AND ticket_id = ? AND phase = ?", stmt -> {
            stmt.setString(1, after);
            stmt.setString(2, network);
            stmt.setString(3, ticket);
            stmt.setString(4, before);
        }) == 1;
    }

    boolean bind(String ticket, String channelId) {
        return sql.update("""
                UPDATE discord_ticket_rooms SET phase = 'BOUND', channel_id = ?, retry_at_ms = 0
                WHERE network_id = ? AND ticket_id = ? AND phase = 'CREATING'
                """, stmt -> {
            stmt.setString(1, channelId);
            stmt.setString(2, network);
            stmt.setString(3, ticket);
        }) == 1;
    }

    void applied(String ticket, String user, String roles, boolean writable) {
        sql.update("""
                UPDATE discord_ticket_rooms SET applied_user = ?, applied_roles = ?, applied_writable = ?, retry_at_ms = 0
                WHERE network_id = ? AND ticket_id = ? AND phase = 'BOUND'
                """, stmt -> {
            stmt.setString(1, user);
            stmt.setString(2, roles);
            stmt.setInt(3, writable ? 1 : 0);
            stmt.setString(4, network);
            stmt.setString(5, ticket);
        });
    }

    boolean openingPending(String ticket, boolean before, boolean after) {
        return sql.update("""
                UPDATE discord_ticket_rooms SET opening_pending = ?
                WHERE network_id = ? AND ticket_id = ? AND phase = 'BOUND' AND opening_pending = ?
                """, stmt -> {
            stmt.setInt(1, after ? 1 : 0);
            stmt.setString(2, network);
            stmt.setString(3, ticket);
            stmt.setInt(4, before ? 1 : 0);
        }) == 1;
    }

    void retryAt(String ticket, long at) {
        sql.update("UPDATE discord_ticket_rooms SET retry_at_ms = ? WHERE network_id = ? AND ticket_id = ?", stmt -> {
            stmt.setLong(1, at);
            stmt.setString(2, network);
            stmt.setString(3, ticket);
        });
    }

    private String insert(String table, String columns, String values, String key) {
        String suffix = "sqlite".equalsIgnoreCase(sql.dialectName())
                ? " ON CONFLICT(" + key + ") DO NOTHING"
                : " ON DUPLICATE KEY UPDATE network_id = VALUES(network_id)";
        return "INSERT INTO " + table + "(" + columns + ") VALUES (" + values + ")" + suffix;
    }
}
