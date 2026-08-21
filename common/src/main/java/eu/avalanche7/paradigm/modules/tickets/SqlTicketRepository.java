package eu.avalanche7.paradigm.modules.tickets;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.sql.SqlExecutor;

public class SqlTicketRepository implements TicketRepository {

    private static final Gson GSON = new Gson();

    private static final String TICKET_COLUMNS = "ticket_id, ticket_key, network_id, origin_server_id, creator_uuid, "
            + "creator_name, category, subject, status, priority, assignee_uuid, assignee_name, created_at_ms, "
            + "updated_at_ms, resolved_at_ms, closed_at_ms, last_activity_at_ms, revision, metadata_json";

    private final SqlExecutor sql;
    private final StorageContext context;

    public SqlTicketRepository(SqlExecutor sql, StorageContext context) {
        this.sql = sql;
        this.context = context;
    }

    @Override
    public long allocateTicketNumber(String networkId) {
        String network = network(networkId);
        return sql.transaction(() -> {
            int updated = sql.update("UPDATE tickets_sequence SET next_value = next_value + 1 WHERE network_id = ?",
                    ps -> ps.setString(1, network));
            if (updated == 0) {
                sql.update("INSERT INTO tickets_sequence(network_id, next_value) VALUES(?, 1)",
                        ps -> ps.setString(1, network));
                return 1L;
            }
            return sql.query("SELECT next_value FROM tickets_sequence WHERE network_id = ?",
                    ps -> ps.setString(1, network),
                    rs -> rs.next() ? rs.getLong("next_value") : 1L);
        });
    }

    @Override
    public TicketWriteResult createTicket(TicketCreate create) {
        Ticket ticket = create.ticket();
        return sql.transaction(() -> {
            sql.update("INSERT INTO tickets(" + TICKET_COLUMNS + ") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ps -> bindTicket(ps, ticket));
            if (create.firstMessage() != null) {
                insertMessage(create.firstMessage());
            }
            if (create.createdEvent() != null) {
                insertEvent(create.createdEvent());
            }
            return TicketWriteResult.ok(ticket);
        });
    }

    @Override
    public TicketWriteResult applyMutation(TicketMutation mutation) {
        return sql.transaction(() -> {
            Ticket updatedTicket = mutation.updated();
            StringBuilder statement = new StringBuilder("UPDATE tickets SET category = ?, subject = ?, status = ?, "
                    + "priority = ?, assignee_uuid = ?, assignee_name = ?, updated_at_ms = ?, resolved_at_ms = ?, "
                    + "closed_at_ms = ?, last_activity_at_ms = ?, revision = revision + 1 "
                    + "WHERE network_id = ? AND ticket_key = ? AND revision = ?");
            if (mutation.requireUnassigned()) {
                statement.append(" AND assignee_uuid IS NULL");
            }
            int updated = sql.update(statement.toString(), ps -> {
                ps.setString(1, updatedTicket.category());
                ps.setString(2, updatedTicket.subject());
                ps.setString(3, updatedTicket.status().name());
                ps.setString(4, updatedTicket.priority().name());
                bindNullableString(ps, 5, updatedTicket.assigneeUuid());
                bindNullableString(ps, 6, updatedTicket.assigneeName());
                ps.setLong(7, updatedTicket.updatedAtMs());
                bindNullableLong(ps, 8, updatedTicket.resolvedAtMs());
                bindNullableLong(ps, 9, updatedTicket.closedAtMs());
                ps.setLong(10, updatedTicket.lastActivityAtMs());
                ps.setString(11, network(mutation.networkId()));
                ps.setString(12, mutation.ticketKey());
                ps.setLong(13, mutation.expectedRevision());
            });
            if (updated == 0) {
                Ticket fresh = findTicket(mutation.networkId(), mutation.ticketKey()).orElse(null);
                if (fresh == null) {
                    return TicketWriteResult.notFound();
                }
                if (mutation.requireUnassigned() && fresh.isAssigned()) {
                    return TicketWriteResult.alreadyClaimed(fresh);
                }
                return TicketWriteResult.stale(fresh);
            }
            if (mutation.message() != null) {
                insertMessage(mutation.message());
            }
            for (TicketEvent event : mutation.events()) {
                insertEvent(event);
            }
            return TicketWriteResult.ok(updatedTicket.withRevision(mutation.expectedRevision() + 1));
        });
    }

    @Override
    public Optional<Ticket> findTicket(String networkId, String ticketKey) {
        if (ticketKey == null) {
            return Optional.empty();
        }
        return sql.query("SELECT " + TICKET_COLUMNS + " FROM tickets WHERE network_id = ? AND ticket_key = ?",
                ps -> {
                    ps.setString(1, network(networkId));
                    ps.setString(2, ticketKey);
                },
                rs -> rs.next() ? Optional.of(readTicket(rs)) : Optional.empty());
    }

    @Override
    public List<Ticket> listTickets(TicketQuery query) {
        List<Object> parameters = new ArrayList<>();
        String where = buildWhere(query, parameters);
        parameters.add(query.pageSize());
        parameters.add(query.offset());
        return sql.query("SELECT " + TICKET_COLUMNS + " FROM tickets " + where
                        + " ORDER BY last_activity_at_ms DESC, created_at_ms DESC LIMIT ? OFFSET ?",
                ps -> bindAll(ps, parameters),
                rs -> {
                    List<Ticket> tickets = new ArrayList<>();
                    while (rs.next()) {
                        tickets.add(readTicket(rs));
                    }
                    return tickets;
                });
    }

    @Override
    public int countTickets(TicketQuery query) {
        List<Object> parameters = new ArrayList<>();
        String where = buildWhere(query, parameters);
        return sql.query("SELECT COUNT(*) AS total FROM tickets " + where,
                ps -> bindAll(ps, parameters),
                rs -> rs.next() ? rs.getInt("total") : 0);
    }

    @Override
    public Map<String, Integer> summaryCounts(String networkId) {
        return sql.query("SELECT status, priority, assignee_uuid FROM tickets WHERE network_id = ?",
                ps -> ps.setString(1, network(networkId)),
                rs -> {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    while (rs.next()) {
                        TicketStatus status = TicketStatus.parseOr(rs.getString("status"), TicketStatus.OPEN);
                        TicketPriority priority = TicketPriority.parseOr(rs.getString("priority"), TicketPriority.NORMAL);
                        String assignee = rs.getString("assignee_uuid");
                        TicketSummaries.accumulate(counts, status, priority, assignee == null || assignee.isBlank());
                    }
                    return TicketSummaries.finish(counts);
                });
    }

    @Override
    public int countActiveByCreator(String networkId, String creatorUuid) {
        if (creatorUuid == null) {
            return 0;
        }
        return sql.query("SELECT COUNT(*) AS total FROM tickets WHERE network_id = ? AND LOWER(creator_uuid) = ? "
                        + "AND status NOT IN ('RESOLVED', 'CLOSED')",
                ps -> {
                    ps.setString(1, network(networkId));
                    ps.setString(2, creatorUuid.toLowerCase(Locale.ROOT));
                },
                rs -> rs.next() ? rs.getInt("total") : 0);
    }

    @Override
    public Optional<Long> lastCreatedAtByCreator(String networkId, String creatorUuid) {
        if (creatorUuid == null) {
            return Optional.empty();
        }
        return sql.query("SELECT MAX(created_at_ms) AS latest FROM tickets WHERE network_id = ? AND LOWER(creator_uuid) = ?",
                ps -> {
                    ps.setString(1, network(networkId));
                    ps.setString(2, creatorUuid.toLowerCase(Locale.ROOT));
                },
                rs -> {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    long value = rs.getLong("latest");
                    return rs.wasNull() ? Optional.empty() : Optional.of(value);
                });
    }

    @Override
    public List<TicketMessage> listMessages(String networkId, String ticketKey, int offset, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeOffset = Math.max(0, offset);
        return sql.query("SELECT message_id, ticket_id, network_id, ticket_key, author_type, author_uuid, author_name, "
                        + "server_id, message_text, created_at_ms FROM ticket_messages "
                        + "WHERE network_id = ? AND ticket_key = ? ORDER BY created_at_ms ASC, message_id ASC LIMIT ? OFFSET ?",
                ps -> {
                    ps.setString(1, network(networkId));
                    ps.setString(2, ticketKey);
                    ps.setInt(3, safeLimit);
                    ps.setInt(4, safeOffset);
                },
                rs -> {
                    List<TicketMessage> messages = new ArrayList<>();
                    while (rs.next()) {
                        messages.add(readMessage(rs));
                    }
                    return messages;
                });
    }

    @Override
    public List<TicketEvent> listEvents(String networkId, String ticketKey) {
        return sql.query("SELECT event_id, ticket_id, network_id, ticket_key, event_type, actor_uuid, actor_name, "
                        + "server_id, old_value, new_value, created_at_ms FROM ticket_events "
                        + "WHERE network_id = ? AND ticket_key = ? ORDER BY created_at_ms ASC, event_id ASC",
                ps -> {
                    ps.setString(1, network(networkId));
                    ps.setString(2, ticketKey);
                },
                rs -> {
                    List<TicketEvent> events = new ArrayList<>();
                    while (rs.next()) {
                        events.add(readEvent(rs));
                    }
                    return events;
                });
    }

    @Override
    public List<Ticket> findAutoCloseCandidates(String networkId, Long resolvedBeforeMs, Long waitingBeforeMs, int limit) {
        if (resolvedBeforeMs == null && waitingBeforeMs == null) {
            return List.of();
        }
        StringBuilder statement = new StringBuilder("SELECT " + TICKET_COLUMNS + " FROM tickets WHERE network_id = ? AND (");
        List<Object> parameters = new ArrayList<>();
        parameters.add(network(networkId));
        List<String> clauses = new ArrayList<>();
        if (resolvedBeforeMs != null) {
            clauses.add("(status = 'RESOLVED' AND updated_at_ms <= ?)");
            parameters.add(resolvedBeforeMs);
        }
        if (waitingBeforeMs != null) {
            clauses.add("(status = 'WAITING_PLAYER' AND updated_at_ms <= ?)");
            parameters.add(waitingBeforeMs);
        }
        statement.append(String.join(" OR ", clauses)).append(") ORDER BY updated_at_ms ASC LIMIT ?");
        parameters.add(Math.max(1, Math.min(limit, 200)));
        return sql.query(statement.toString(), ps -> bindAll(ps, parameters), rs -> {
            List<Ticket> tickets = new ArrayList<>();
            while (rs.next()) {
                tickets.add(readTicket(rs));
            }
            return tickets;
        });
    }

    @Override
    public List<TicketEvent> listEventsSince(String networkId, long sinceMs, String excludeServerId, int limit) {
        return listEventsSince(networkId, sinceMs, null, excludeServerId, limit);
    }

    @Override
    public List<TicketEvent> listEventsSince(String networkId, long sinceMs, String afterEventId,
                                             String excludeServerId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return sql.query("SELECT event_id, ticket_id, network_id, ticket_key, event_type, actor_uuid, actor_name, "
                        + "server_id, old_value, new_value, created_at_ms FROM ticket_events "
                        + "WHERE network_id = ? AND (created_at_ms > ? OR (created_at_ms = ? AND event_id > ?)) "
                        + "AND (server_id IS NULL OR server_id <> ?) "
                        + "ORDER BY created_at_ms ASC, event_id ASC LIMIT ?",
                ps -> {
                    ps.setString(1, network(networkId));
                    ps.setLong(2, sinceMs);
                    ps.setLong(3, sinceMs);
                    ps.setString(4, afterEventId != null ? afterEventId : "");
                    ps.setString(5, excludeServerId != null ? excludeServerId : "");
                    ps.setInt(6, safeLimit);
                },
                rs -> {
                    List<TicketEvent> events = new ArrayList<>();
                    while (rs.next()) {
                        events.add(readEvent(rs));
                    }
                    return events;
                });
    }

    @Override
    public boolean supportsCrossServerFeed() {
        return true;
    }

    private void insertMessage(TicketMessage message) {
        sql.update("INSERT INTO ticket_messages(message_id, ticket_id, network_id, ticket_key, author_type, "
                + "author_uuid, author_name, server_id, message_text, created_at_ms) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, message.messageId());
            ps.setString(2, message.ticketId());
            ps.setString(3, network(message.networkId()));
            ps.setString(4, message.ticketKey());
            ps.setString(5, message.authorType().name());
            bindNullableString(ps, 6, message.authorUuid());
            bindNullableString(ps, 7, message.authorName());
            bindNullableString(ps, 8, message.serverId());
            ps.setString(9, message.text());
            ps.setLong(10, message.createdAtMs());
        });
    }

    private void insertEvent(TicketEvent event) {
        sql.update("INSERT INTO ticket_events(event_id, ticket_id, network_id, ticket_key, event_type, actor_uuid, "
                + "actor_name, server_id, old_value, new_value, created_at_ms) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, event.eventId());
            ps.setString(2, event.ticketId());
            ps.setString(3, network(event.networkId()));
            ps.setString(4, event.ticketKey());
            ps.setString(5, event.eventType().name());
            bindNullableString(ps, 6, event.actorUuid());
            bindNullableString(ps, 7, event.actorName());
            bindNullableString(ps, 8, event.serverId());
            bindNullableString(ps, 9, event.oldValue());
            bindNullableString(ps, 10, event.newValue());
            ps.setLong(11, event.createdAtMs());
        });
    }

    private String buildWhere(TicketQuery query, List<Object> parameters) {
        StringBuilder where = new StringBuilder("WHERE network_id = ?");
        parameters.add(network(query.networkId()));
        if (!query.statuses().isEmpty()) {
            where.append(" AND status IN (").append(placeholders(query.statuses().size())).append(')');
            query.statuses().forEach(status -> parameters.add(status.name()));
        }
        if (!query.priorities().isEmpty()) {
            where.append(" AND priority IN (").append(placeholders(query.priorities().size())).append(')');
            query.priorities().forEach(priority -> parameters.add(priority.name()));
        }
        if (query.category() != null) {
            where.append(" AND LOWER(category) = ?");
            parameters.add(query.category());
        }
        if (query.originServerId() != null) {
            where.append(" AND LOWER(origin_server_id) = ?");
            parameters.add(query.originServerId());
        }
        if (query.unassignedOnly()) {
            where.append(" AND (assignee_uuid IS NULL OR assignee_uuid = '')");
        }
        if (query.assigneeUuid() != null) {
            where.append(" AND LOWER(assignee_uuid) = ?");
            parameters.add(query.assigneeUuid());
        }
        if (query.creatorUuid() != null) {
            where.append(" AND LOWER(creator_uuid) = ?");
            parameters.add(query.creatorUuid());
        }
        if (query.search() != null) {
            where.append(" AND (LOWER(ticket_key) LIKE ? OR LOWER(subject) LIKE ? OR LOWER(creator_name) LIKE ?"
                    + " OR LOWER(assignee_name) LIKE ?)");
            String pattern = '%' + query.search() + '%';
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
        }
        return where.toString();
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static void bindAll(PreparedStatement ps, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value instanceof Long longValue) {
                ps.setLong(index + 1, longValue);
            } else if (value instanceof Integer intValue) {
                ps.setInt(index + 1, intValue);
            } else {
                ps.setString(index + 1, String.valueOf(value));
            }
        }
    }

    private void bindTicket(PreparedStatement ps, Ticket ticket) throws SQLException {
        ps.setString(1, ticket.ticketId());
        ps.setString(2, ticket.ticketKey());
        ps.setString(3, network(ticket.networkId()));
        bindNullableString(ps, 4, ticket.originServerId());
        bindNullableString(ps, 5, ticket.creatorUuid());
        bindNullableString(ps, 6, ticket.creatorName());
        ps.setString(7, ticket.category());
        ps.setString(8, ticket.subject());
        ps.setString(9, ticket.status().name());
        ps.setString(10, ticket.priority().name());
        bindNullableString(ps, 11, ticket.assigneeUuid());
        bindNullableString(ps, 12, ticket.assigneeName());
        ps.setLong(13, ticket.createdAtMs());
        ps.setLong(14, ticket.updatedAtMs());
        bindNullableLong(ps, 15, ticket.resolvedAtMs());
        bindNullableLong(ps, 16, ticket.closedAtMs());
        ps.setLong(17, ticket.lastActivityAtMs());
        ps.setLong(18, ticket.revision());
        ps.setString(19, GSON.toJson(ticket.metadata()));
    }

    private static Ticket readTicket(ResultSet rs) throws SQLException {
        String metadataJson = rs.getString("metadata_json");
        Map<String, String> metadata = Map.of();
        if (metadataJson != null && !metadataJson.isBlank()) {
            Map<String, String> parsed = GSON.fromJson(metadataJson, new TypeToken<Map<String, String>>() { }.getType());
            if (parsed != null) {
                metadata = parsed;
            }
        }
        return new Ticket(
                rs.getString("ticket_id"),
                rs.getString("ticket_key"),
                rs.getString("network_id"),
                rs.getString("origin_server_id"),
                rs.getString("creator_uuid"),
                rs.getString("creator_name"),
                rs.getString("category"),
                rs.getString("subject"),
                TicketStatus.parseOr(rs.getString("status"), TicketStatus.OPEN),
                TicketPriority.parseOr(rs.getString("priority"), TicketPriority.NORMAL),
                rs.getString("assignee_uuid"),
                rs.getString("assignee_name"),
                rs.getLong("created_at_ms"),
                rs.getLong("updated_at_ms"),
                nullableLong(rs, "resolved_at_ms"),
                nullableLong(rs, "closed_at_ms"),
                rs.getLong("last_activity_at_ms"),
                rs.getLong("revision"),
                metadata
        );
    }

    private static TicketMessage readMessage(ResultSet rs) throws SQLException {
        return new TicketMessage(
                rs.getString("message_id"),
                rs.getString("ticket_id"),
                rs.getString("network_id"),
                rs.getString("ticket_key"),
                TicketAuthorType.parseOr(rs.getString("author_type"), TicketAuthorType.SYSTEM),
                rs.getString("author_uuid"),
                rs.getString("author_name"),
                rs.getString("server_id"),
                rs.getString("message_text"),
                rs.getLong("created_at_ms")
        );
    }

    private static TicketEvent readEvent(ResultSet rs) throws SQLException {
        return new TicketEvent(
                rs.getString("event_id"),
                rs.getString("ticket_id"),
                rs.getString("network_id"),
                rs.getString("ticket_key"),
                TicketEventType.parseOr(rs.getString("event_type"), TicketEventType.STATUS_CHANGED),
                rs.getString("actor_uuid"),
                rs.getString("actor_name"),
                rs.getString("server_id"),
                rs.getString("old_value"),
                rs.getString("new_value"),
                rs.getLong("created_at_ms")
        );
    }

    private static void bindNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void bindNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String network(String networkId) {
        if (networkId != null && !networkId.isBlank()) {
            return networkId;
        }
        return context != null ? context.networkId() : "default";
    }
}
