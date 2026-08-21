package eu.avalanche7.paradigm.modules.dashboard.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardMutationFeedback;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.tickets.Ticket;
import eu.avalanche7.paradigm.modules.tickets.TicketActor;
import eu.avalanche7.paradigm.modules.tickets.TicketCategories;
import eu.avalanche7.paradigm.modules.tickets.TicketError;
import eu.avalanche7.paradigm.modules.tickets.TicketEvent;
import eu.avalanche7.paradigm.modules.tickets.TicketMessage;
import eu.avalanche7.paradigm.modules.tickets.TicketOutcome;
import eu.avalanche7.paradigm.modules.tickets.TicketPage;
import eu.avalanche7.paradigm.modules.tickets.TicketPriority;
import eu.avalanche7.paradigm.modules.tickets.TicketQuery;
import eu.avalanche7.paradigm.modules.tickets.TicketService;
import eu.avalanche7.paradigm.modules.tickets.TicketStatus;

public final class TicketApiHandler {

    public static final Set<String> ACTIONS = Set.of(
            "reply", "claim", "unclaim", "assign", "priority", "status", "category",
            "resolve", "close", "reopen");

    private final DashboardService dashboard;

    public TicketApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse list(DashboardRequestContext ctx) {
        TicketService tickets = enabledTickets();
        if (tickets == null) {
            return disabledOrUnavailable();
        }
        Map<String, String> query = ctx.query();
        TicketQuery.Builder builder = TicketQuery.builder(tickets.networkId())
                .statuses(parseStatuses(query.get("status")))
                .priorities(parsePriorities(query.get("priority")))
                .category(query.get("category"))
                .originServerId(query.get("server"))
                .creatorUuid(query.get("creator"))
                .search(query.get("search"))
                .page(positiveInt(query.get("page"), 1))
                .pageSize(Math.max(10, Math.min(positiveInt(query.get("pageSize"), 25), 100)));
        String assignee = query.get("assignee");
        if ("unassigned".equalsIgnoreCase(assignee)) {
            builder.unassignedOnly(true);
        } else if (assignee != null && !assignee.isBlank()) {
            builder.assigneeUuid(assignee);
        }

        TicketActor actor = actor(ctx);
        TicketService.VisiblePage visible = tickets.listVisible(actor, builder.build());
        TicketPage page = visible.page();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Ticket ticket : page.tickets()) {
            entries.add(ticketDto(ticket));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entries", entries);
        payload.put("total", page.total());
        payload.put("page", page.page());
        payload.put("pageSize", page.pageSize());
        payload.put("summary", visible.summary());
        payload.put("statuses", names(TicketStatus.values()));
        payload.put("priorities", names(TicketPriority.values()));
        payload.put("categories", categories(actor));
        return DashboardResponse.apiOk(payload);
    }

    public DashboardResponse get(DashboardRequestContext ctx) {
        TicketService tickets = enabledTickets();
        if (tickets == null) {
            return disabledOrUnavailable();
        }
        String key = ctx.query().get("id");
        TicketActor actor = actor(ctx);
        Optional<Ticket> found = tickets.find(actor, key);
        if (found.isEmpty()) {
            return DashboardResponse.apiError(404, "ticket_not_found", "Ticket was not found.");
        }
        Ticket ticket = found.get();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (TicketMessage message : tickets.messages(actor, key, 0, 500)) {
            messages.add(messageDto(message));
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (TicketEvent event : tickets.history(actor, key)) {
            events.add(eventDto(event));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticket", ticketDto(ticket));
        payload.put("messages", messages);
        payload.put("events", events);
        return DashboardResponse.apiOk(payload);
    }

    public DashboardResponse mutate(DashboardRequestContext ctx, String action) {
        TicketService tickets = enabledTickets();
        if (tickets == null) {
            return disabledOrUnavailable();
        }
        if (!ACTIONS.contains(action)) {
            return DashboardResponse.apiError(400, "invalid_request", "Unknown ticket operation.");
        }
        Request parsed = DashboardJson.fromJson(ctx.bodyReader(), Request.class);
        final Request request = parsed != null ? parsed : new Request();
        if (request.id == null || request.id.isBlank()) {
            return DashboardResponse.apiError(400, "invalid_request", "A ticket id is required.");
        }
        TicketActor actor = actor(ctx);
        Long expectedRevision = request.revision;
        TicketOutcome outcome = switch (action) {
            case "reply" -> tickets.reply(actor, request.id, request.message, expectedRevision);
            case "claim" -> tickets.claim(actor, request.id, expectedRevision);
            case "unclaim" -> tickets.unclaim(actor, request.id, expectedRevision);
            case "assign" -> tickets.assign(actor, request.id, request.assignee, expectedRevision);
            case "priority" -> TicketPriority.parse(request.value)
                    .map(priority -> tickets.changePriority(actor, request.id, priority, expectedRevision))
                    .orElseGet(() -> TicketOutcome.fail(TicketError.INVALID_PRIORITY));
            case "status" -> TicketStatus.parse(request.value)
                    .map(status -> tickets.changeStatus(actor, request.id, status, expectedRevision))
                    .orElseGet(() -> TicketOutcome.fail(TicketError.INVALID_STATUS));
            case "category" -> tickets.changeCategory(actor, request.id, request.value, expectedRevision);
            case "resolve" -> tickets.resolve(actor, request.id, expectedRevision);
            case "close" -> tickets.close(actor, request.id, expectedRevision);
            default -> tickets.reopen(actor, request.id, expectedRevision);
        };

        if (outcome.ok()) {
            notifyMutation(ctx, action, outcome.ticket());
            return DashboardResponse.apiOk(Map.of("ticket", ticketDto(outcome.ticket())));
        }
        return errorResponse(action, outcome);
    }

    private DashboardResponse errorResponse(String action, TicketOutcome outcome) {
        TicketError error = outcome.error();
        Map<String, Object> data = new LinkedHashMap<>();
        data.putAll(outcome.details());
        data.put("action", action);
        if (outcome.ticket() != null) {
            data.put("ticket", ticketDto(outcome.ticket()));
            data.put("ticketKey", outcome.ticket().ticketKey());
        }
        int status = switch (error) {
            case TICKET_NOT_FOUND -> 404;
            case PERMISSION_DENIED -> 403;
            case STALE_TICKET, ALREADY_CLAIMED -> 409;
            case STORAGE_UNAVAILABLE, TICKETS_DISABLED -> 503;
            default -> 400;
        };
        return DashboardResponse.json(status, new DashboardResponse.ApiEnvelope(false, data,
                new DashboardResponse.ApiError(error.code(), message(error, outcome)), List.of()));
    }

    private static String message(TicketError error, TicketOutcome outcome) {
        String text = error.fallback();
        if (outcome.ticket() != null) {
            text = text.replace("{ticket}", outcome.ticket().ticketKey());
        }
        for (Map.Entry<String, String> detail : outcome.details().entrySet()) {
            text = text.replace("{" + detail.getKey() + "}", String.valueOf(detail.getValue()));
        }
        return text;
    }

    private void notifyMutation(DashboardRequestContext ctx, String action, Ticket ticket) {
        if (dashboard.services() == null || dashboard.services().getPlatformAdapter() == null) {
            return;
        }
        dashboard.services().getPlatformAdapter().executeOnServerThread(() -> {
            try {
                DashboardMutationFeedback.notify(dashboard.services(), ctx.principal(), ctx.header("X-Paradigm-Locale"),
                        DashboardMutationFeedback.Area.TICKETS,
                        List.of(DashboardMutationFeedback.info(ticket.ticketKey() + " " + action)));
            } catch (RuntimeException | LinkageError ignored) {
            }
        });
    }

    private static Map<String, Object> ticketDto(Ticket ticket) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("ticketKey", ticket.ticketKey());
        dto.put("networkId", ticket.networkId());
        dto.put("originServerId", ticket.originServerId());
        dto.put("creatorUuid", ticket.creatorUuid());
        dto.put("creatorName", ticket.creatorName());
        dto.put("category", ticket.category());
        dto.put("subject", ticket.subject());
        dto.put("status", ticket.status().name());
        dto.put("priority", ticket.priority().name());
        dto.put("assigneeUuid", ticket.assigneeUuid());
        dto.put("assigneeName", ticket.assigneeName());
        dto.put("createdAtMs", ticket.createdAtMs());
        dto.put("updatedAtMs", ticket.updatedAtMs());
        dto.put("resolvedAtMs", ticket.resolvedAtMs());
        dto.put("closedAtMs", ticket.closedAtMs());
        dto.put("lastActivityAtMs", ticket.lastActivityAtMs());
        dto.put("revision", ticket.revision());
        return dto;
    }

    private static Map<String, Object> messageDto(TicketMessage message) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("messageId", message.messageId());
        dto.put("authorType", message.authorType().name());
        dto.put("authorUuid", message.authorUuid());
        dto.put("authorName", message.authorName());
        dto.put("serverId", message.serverId());
        dto.put("text", message.text());
        dto.put("createdAtMs", message.createdAtMs());
        return dto;
    }

    private static Map<String, Object> eventDto(TicketEvent event) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("eventId", event.eventId());
        dto.put("eventType", event.eventType().name());
        dto.put("actorUuid", event.actorUuid());
        dto.put("actorName", event.actorName());
        dto.put("serverId", event.serverId());
        dto.put("oldValue", event.oldValue());
        dto.put("newValue", event.newValue());
        dto.put("createdAtMs", event.createdAtMs());
        return dto;
    }

    private List<Map<String, Object>> categories(TicketActor actor) {
        List<Map<String, Object>> entries = new ArrayList<>();
        TicketService tickets = enabledTickets();
        if (tickets == null || tickets.config() == null) {
            return entries;
        }
        for (var category : TicketCategories.enabled(tickets.config())) {
            if (!TicketCategories.mayStaffHandle(category, actor)) {
                continue;
            }
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", category.id);
            dto.put("displayName", category.displayName);
            dto.put("description", category.description);
            dto.put("defaultPriority", category.defaultPriority);
            entries.add(dto);
        }
        return entries;
    }

    private TicketService enabledTickets() {
        TicketService service = tickets();
        return service != null && service.enabled() ? service : null;
    }

    private DashboardResponse disabledOrUnavailable() {
        TicketService service = tickets();
        if (service != null && !service.enabled()) {
            return DashboardResponse.apiError(503, "tickets_disabled", "The ticket system is disabled.");
        }
        return DashboardResponse.apiError(503, "unavailable", "The ticket system is unavailable.");
    }

    private TicketService tickets() {
        try {
            return dashboard.services() != null ? dashboard.services().getTicketService() : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private TicketActor actor(DashboardRequestContext ctx) {
        String uuid = ctx.principal() != null ? ctx.principal().uuid() : null;
        String name = ctx.principal() != null ? ctx.principal().name() : "Dashboard";
        return TicketActor.administrative(uuid, name,
                permission -> dashboard.hasPermission(ctx.principal(), permission));
    }

    static Set<TicketStatus> parseStatuses(String raw) {
        Set<TicketStatus> statuses = new java.util.LinkedHashSet<>();
        for (String token : split(raw)) {
            TicketStatus.parse(token).ifPresent(statuses::add);
        }
        return statuses;
    }

    static Set<TicketPriority> parsePriorities(String raw) {
        Set<TicketPriority> priorities = new java.util.LinkedHashSet<>();
        for (String token : split(raw)) {
            TicketPriority.parse(token).ifPresent(priorities::add);
        }
        return priorities;
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> names = new ArrayList<>();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }

    static int positiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static String normalizeAction(String path, String prefix) {
        if (path == null || !path.startsWith(prefix)) {
            return "";
        }
        return path.substring(prefix.length()).toLowerCase(Locale.ROOT);
    }

    public static final class Request {
        public String id = "";
        public String message = "";
        public String value = "";
        public String assignee = "";
        public Long revision;
    }
}
