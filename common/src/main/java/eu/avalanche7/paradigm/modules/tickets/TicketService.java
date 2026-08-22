package eu.avalanche7.paradigm.modules.tickets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import eu.avalanche7.paradigm.configs.ConfigEntry;
import eu.avalanche7.paradigm.configs.TicketsConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmEvents;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;

public class TicketService {

    private final Services services;
    private volatile TicketNotifier notifier;

    public TicketService(Services services) {
        this.services = services;
    }

    public void setNotifier(TicketNotifier notifier) {
        this.notifier = notifier;
    }

    public TicketOutcome create(TicketActor actor, String categoryId, String message) {
        TicketsConfigHandler.Config config = config();
        if (!enabled(config)) {
            return TicketOutcome.fail(TicketError.TICKETS_DISABLED);
        }
        if (!actor.has(ParadigmPermissions.TICKET_CREATE)) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED);
        }
        int maxMessageLength = ConfigEntry.valueOf(config.maxMessageLength, 512);
        TicketError messageError = TicketWorkflow.validateMessage(message, maxMessageLength);
        if (messageError.isFailure()) {
            return TicketOutcome.fail(messageError, null, Map.of("limit", String.valueOf(maxMessageLength)));
        }
        TicketsConfigHandler.CategoryEntry category = TicketCategories.resolve(config, categoryId);
        if (category == null) {
            return TicketOutcome.fail(TicketError.INVALID_CATEGORY);
        }
        if (!TicketCategories.mayUse(category, actor)) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED);
        }

        TicketRepository repository = repository();
        if (repository == null) {
            return TicketOutcome.fail(TicketError.STORAGE_UNAVAILABLE);
        }
        String networkId = networkId();
        String creatorUuid = actor.uuid();
        long now = System.currentTimeMillis();

        int maxOpen = ConfigEntry.valueOf(config.maxOpenTicketsPerPlayer, 3);

        final long number;
        try {
            number = repository.allocateTicketNumber(networkId);
        } catch (RuntimeException allocationFailure) {
            return TicketOutcome.fail(TicketError.STORAGE_UNAVAILABLE);
        }
        String ticketKey = TicketIds.key(number);
        String ticketId = TicketIds.ticketId();
        String serverId = serverId();
        String subject = TicketWorkflow.deriveSubject(message, ConfigEntry.valueOf(config.maxSubjectLength, 64));

        Ticket ticket = new Ticket(ticketId, ticketKey, networkId, serverId, creatorUuid, actor.name(),
                category.id.toLowerCase(Locale.ROOT), subject, TicketStatus.OPEN,
                TicketCategories.defaultPriority(category), null, null,
                now, now, null, null, now, 0L, Map.of());

        TicketMessage firstMessage = new TicketMessage(TicketIds.messageId(), ticketId, networkId, ticketKey,
                TicketAuthorType.PLAYER, creatorUuid, actor.name(), serverId, message.strip(), now);
        TicketEvent createdEvent = new TicketEvent(TicketIds.eventId(), ticketId, networkId, ticketKey,
                TicketEventType.CREATED, creatorUuid, actor.name(), serverId, null, TicketStatus.OPEN.name(), now);

        TicketWriteResult result = repository.createTicketIfAllowed(new TicketCreate(ticket, firstMessage, createdEvent), maxOpen,
                ConfigEntry.valueOf(config.createCooldownSeconds, 120) * 1000L);
        if (result.status() == TicketWriteResult.Status.LIMIT_REACHED) {
            return TicketOutcome.fail(TicketError.TICKET_LIMIT_REACHED, null, Map.of("limit", String.valueOf(maxOpen)));
        }
        if (result.status() == TicketWriteResult.Status.COOLDOWN) {
            long remainingMs = result.cooldownRemainingMs() != null
                    ? result.cooldownRemainingMs()
                    : ConfigEntry.valueOf(config.createCooldownSeconds, 120) * 1000L;
            return TicketOutcome.fail(TicketError.TICKET_COOLDOWN, null,
                    Map.of("remainingMs", String.valueOf(remainingMs)));
        }
        if (!result.ok()) {
            return translate(result, ticketKey);
        }
        TicketEvent admittedEvent = new TicketEvent(createdEvent.eventId(), createdEvent.ticketId(), createdEvent.networkId(),
                createdEvent.ticketKey(), createdEvent.eventType(), createdEvent.actorUuid(), createdEvent.actorName(),
                createdEvent.serverId(), createdEvent.oldValue(), createdEvent.newValue(), result.ticket().createdAtMs());
        publish(events -> events.ticketCreated(result.ticket(), admittedEvent));
        notify(target -> target.ticketCreated(result.ticket(), admittedEvent));
        return TicketOutcome.ok(result.ticket());
    }

    public TicketOutcome reply(TicketActor actor, String ticketKey, String message, Long expectedRevision) {
        TicketsConfigHandler.Config config = config();
        if (!enabled(config)) {
            return TicketOutcome.fail(TicketError.TICKETS_DISABLED);
        }
        int maxMessageLength = ConfigEntry.valueOf(config.maxMessageLength, 512);
        TicketError messageError = TicketWorkflow.validateMessage(message, maxMessageLength);
        if (messageError.isFailure()) {
            return TicketOutcome.fail(messageError, null, Map.of("limit", String.valueOf(maxMessageLength)));
        }
        Ticket ticket = load(ticketKey);
        if (ticket == null) {
            return TicketOutcome.fail(TicketError.TICKET_NOT_FOUND);
        }
        boolean creator = ticket.isCreatedBy(actor.uuid());
        boolean staff = actor.has(ParadigmPermissions.TICKET_STAFF_REPLY) && canHandle(ticket, actor);
        if (!staff && !(creator && actor.has(ParadigmPermissions.TICKET_REPLY))) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED, ticket);
        }
        if (!TicketWorkflow.acceptsReply(ticket)) {
            return TicketOutcome.fail(TicketError.TICKET_CLOSED, ticket);
        }

        TicketAuthorType authorType = staff && !creator ? TicketAuthorType.STAFF : TicketAuthorType.PLAYER;
        long now = System.currentTimeMillis();
        TicketStatus nextStatus = TicketWorkflow.statusAfterReply(ticket.status(), authorType);
        Ticket updated = TicketWorkflow.applyStatus(ticket, nextStatus, now);

        TicketMessage newMessage = new TicketMessage(TicketIds.messageId(), ticket.ticketId(), ticket.networkId(),
                ticket.ticketKey(), authorType, actor.uuid(), actor.name(), serverId(), message.strip(), now);
        List<TicketEvent> events = eventsWithStatusChange(ticket, TicketEventType.REPLIED, actor,
                null, authorType.name(), nextStatus, now);

        TicketWriteResult result = apply(TicketMutation.of(updated, revisionOf(ticket, expectedRevision), newMessage, events));
        if (!result.ok()) {
            return translate(result, ticketKey);
        }
        TicketEvent replied = events.get(0);
        if (staff && !creator) {
            audit(actor, result.ticket(), "reply", Map.of("authorType", authorType.name()));
        }
        publish(bus -> bus.ticketReplied(result.ticket(), replied));
        notify(target -> target.ticketReplied(result.ticket(), replied, newMessage));
        return TicketOutcome.ok(result.ticket());
    }

    public TicketOutcome claim(TicketActor actor, String ticketKey, Long expectedRevision) {
        if (!enabled()) {
            return TicketOutcome.fail(TicketError.TICKETS_DISABLED);
        }
        Ticket ticket = load(ticketKey);
        if (ticket == null) {
            return TicketOutcome.fail(TicketError.TICKET_NOT_FOUND);
        }
        if (!actor.has(ParadigmPermissions.TICKET_STAFF_CLAIM) || !canHandle(ticket, actor)) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED, ticket);
        }
        if (!TicketWorkflow.acceptsStaffWorkflow(ticket)) {
            return TicketOutcome.fail(TicketError.TICKET_CLOSED, ticket);
        }
        if (ticket.isAssigned()) {
            return TicketOutcome.fail(TicketError.ALREADY_CLAIMED, ticket,
                    Map.of("actor", String.valueOf(ticket.assigneeName())));
        }
        long now = System.currentTimeMillis();
        TicketStatus nextStatus = TicketWorkflow.statusAfterClaim(ticket.status());
        Ticket updated = TicketWorkflow.applyStatus(ticket, nextStatus, now)
                .toBuilder().assignee(actor.uuid(), actor.name()).build();
        List<TicketEvent> events = eventsWithStatusChange(ticket, TicketEventType.CLAIMED, actor,
                null, actor.name(), nextStatus, now);

        TicketWriteResult result = apply(TicketMutation.claim(updated, revisionOf(ticket, expectedRevision), events));
        if (!result.ok()) {
            return translate(result, ticketKey);
        }
        audit(actor, result.ticket(), "claim", Map.of("assignee", String.valueOf(actor.name())));
        publish(bus -> bus.ticketClaimed(result.ticket(), events.get(0)));
        notify(target -> target.ticketClaimed(result.ticket(), events.get(0)));
        return TicketOutcome.ok(result.ticket());
    }

    public TicketOutcome unclaim(TicketActor actor, String ticketKey, Long expectedRevision) {
        if (!enabled()) {
            return TicketOutcome.fail(TicketError.TICKETS_DISABLED);
        }
        Ticket ticket = load(ticketKey);
        if (ticket == null) {
            return TicketOutcome.fail(TicketError.TICKET_NOT_FOUND);
        }
        if (!actor.has(ParadigmPermissions.TICKET_STAFF_CLAIM) || !canHandle(ticket, actor)) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED, ticket);
        }
        if (!ticket.isAssigned()) {
            return TicketOutcome.fail(TicketError.NOT_CLAIMED, ticket);
        }
        if (!TicketWorkflow.acceptsStaffWorkflow(ticket)) {
            return TicketOutcome.fail(TicketError.TICKET_CLOSED, ticket);
        }
        long now = System.currentTimeMillis();
        TicketStatus nextStatus = TicketWorkflow.statusAfterUnclaim(ticket.status());
        Ticket updated = TicketWorkflow.applyStatus(ticket, nextStatus, now)
                .toBuilder().assignee(null, null).build();
        List<TicketEvent> events = List.of(
                event(ticket, TicketEventType.UNCLAIMED, actor, ticket.assigneeName(), null, now));

        TicketWriteResult result = apply(TicketMutation.of(updated, revisionOf(ticket, expectedRevision), null, events));
        if (!result.ok()) {
            return translate(result, ticketKey);
        }
        audit(actor, result.ticket(), "unclaim", Map.of("previousAssignee", String.valueOf(ticket.assigneeName())));
        return TicketOutcome.ok(result.ticket());
    }

    public TicketOutcome assign(TicketActor actor, String ticketKey, String targetName, Long expectedRevision) {
        if (!enabled()) {
            return TicketOutcome.fail(TicketError.TICKETS_DISABLED);
        }
        Ticket ticket = load(ticketKey);
        if (ticket == null) {
            return TicketOutcome.fail(TicketError.TICKET_NOT_FOUND);
        }
        if (!actor.has(ParadigmPermissions.TICKET_STAFF_ASSIGN) || !canHandle(ticket, actor)) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED, ticket);
        }
        if (!TicketWorkflow.acceptsStaffWorkflow(ticket)) {
            return TicketOutcome.fail(TicketError.TICKET_CLOSED, ticket);
        }
        Optional<TicketIdentity> resolved = resolveIdentity(targetName);
        if (resolved.isEmpty()) {
            return TicketOutcome.fail(TicketError.INVALID_ASSIGNEE, ticket,
                    Map.of("actor", String.valueOf(targetName)));
        }
        return assignResolved(actor, ticket, resolved.get(), expectedRevision);
    }

    private TicketOutcome assignResolved(TicketActor actor, Ticket ticket, TicketIdentity target, Long expectedRevision) {
        long now = System.currentTimeMillis();
        TicketStatus nextStatus = TicketWorkflow.statusAfterClaim(ticket.status());
        Ticket updated = TicketWorkflow.applyStatus(ticket, nextStatus, now)
                .toBuilder().assignee(target.uuid(), target.name()).build();
        List<TicketEvent> events = eventsWithStatusChange(ticket, TicketEventType.ASSIGNED, actor,
                ticket.assigneeName(), target.name(), nextStatus, now);

        TicketWriteResult result = apply(TicketMutation.of(updated, revisionOf(ticket, expectedRevision), null, events));
        if (!result.ok()) {
            return translate(result, ticket.ticketKey());
        }
        audit(actor, result.ticket(), "assign", Map.of("assignee", String.valueOf(target.name()),
                "assigneeUuid", String.valueOf(target.uuid()), "assigneeOnline", String.valueOf(target.online())));
        publish(bus -> bus.ticketAssigned(result.ticket(), events.get(0)));
        notify(target2 -> target2.ticketAssigned(result.ticket(), events.get(0)));
        return TicketOutcome.ok(result.ticket());
    }

    public TicketOutcome changePriority(TicketActor actor, String ticketKey, TicketPriority priority, Long expectedRevision) {
        if (!enabled()) {
            return TicketOutcome.fail(TicketError.TICKETS_DISABLED);
        }
        if (priority == null) {
            return TicketOutcome.fail(TicketError.INVALID_PRIORITY);
        }
        Ticket ticket = load(ticketKey);
        if (ticket == null) {
            return TicketOutcome.fail(TicketError.TICKET_NOT_FOUND);
        }
        boolean staff = actor.has(ParadigmPermissions.TICKET_STAFF_PRIORITY) && canHandle(ticket, actor);
        boolean creator = ticket.isCreatedBy(actor.uuid());
        if (!staff && !creator) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED, ticket);
        }
        if (priority == TicketPriority.URGENT && !staff && !mayUseUrgent(actor)) {
            return TicketOutcome.fail(TicketError.URGENT_NOT_ALLOWED, ticket);
        }
        if (!TicketWorkflow.acceptsStaffWorkflow(ticket)) {
            return TicketOutcome.fail(TicketError.TICKET_CLOSED, ticket);
        }
        long now = System.currentTimeMillis();
        Ticket updated = ticket.toBuilder().priority(priority).touch(now).build();
        List<TicketEvent> events = List.of(event(ticket, TicketEventType.PRIORITY_CHANGED, actor,
                ticket.priority().name(), priority.name(), now));

        TicketWriteResult result = apply(TicketMutation.of(updated, revisionOf(ticket, expectedRevision), null, events));
        if (!result.ok()) {
            return translate(result, ticketKey);
        }
        audit(actor, result.ticket(), "priority", Map.of("oldPriority", ticket.priority().name(),
                "newPriority", priority.name()));
        return TicketOutcome.ok(result.ticket());
    }

    public TicketOutcome changeCategory(TicketActor actor, String ticketKey, String categoryId, Long expectedRevision) {
        if (!enabled()) {
            return TicketOutcome.fail(TicketError.TICKETS_DISABLED);
        }
        Ticket ticket = load(ticketKey);
        if (ticket == null) {
            return TicketOutcome.fail(TicketError.TICKET_NOT_FOUND);
        }
        if (!actor.has(ParadigmPermissions.TICKET_STAFF_STATUS) || !canHandle(ticket, actor)) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED, ticket);
        }
        TicketsConfigHandler.CategoryEntry category = TicketCategories.resolve(config(), categoryId);
        if (category == null) {
            return TicketOutcome.fail(TicketError.INVALID_CATEGORY, ticket);
        }
        if (!TicketWorkflow.acceptsStaffWorkflow(ticket)) {
            return TicketOutcome.fail(TicketError.TICKET_CLOSED, ticket);
        }
        long now = System.currentTimeMillis();
        String newCategory = category.id.toLowerCase(Locale.ROOT);
        Ticket updated = ticket.toBuilder().category(newCategory).touch(now).build();
        List<TicketEvent> events = List.of(event(ticket, TicketEventType.CATEGORY_CHANGED, actor,
                ticket.category(), newCategory, now));

        TicketWriteResult result = apply(TicketMutation.of(updated, revisionOf(ticket, expectedRevision), null, events));
        if (!result.ok()) {
            return translate(result, ticketKey);
        }
        audit(actor, result.ticket(), "category", Map.of("oldCategory", ticket.category(), "newCategory", newCategory));
        return TicketOutcome.ok(result.ticket());
    }

    public TicketOutcome changeStatus(TicketActor actor, String ticketKey, TicketStatus status, Long expectedRevision) {
        if (!enabled()) {
            return TicketOutcome.fail(TicketError.TICKETS_DISABLED);
        }
        if (status == null) {
            return TicketOutcome.fail(TicketError.INVALID_STATUS);
        }
        if (status == TicketStatus.RESOLVED) {
            return resolve(actor, ticketKey, expectedRevision);
        }
        if (status == TicketStatus.CLOSED) {
            return close(actor, ticketKey, expectedRevision);
        }
        Ticket ticket = load(ticketKey);
        if (ticket == null) {
            return TicketOutcome.fail(TicketError.TICKET_NOT_FOUND);
        }
        if (!actor.has(ParadigmPermissions.TICKET_STAFF_STATUS) || !canHandle(ticket, actor)) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED, ticket);
        }
        if (ticket.status() == TicketStatus.CLOSED) {
            return TicketOutcome.fail(TicketError.TICKET_CLOSED, ticket);
        }
        if (ticket.status() == TicketStatus.RESOLVED && !actor.has(ParadigmPermissions.TICKET_STAFF_REOPEN)) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED, ticket);
        }
        long now = System.currentTimeMillis();
        Ticket updated = TicketWorkflow.applyStatus(ticket, status, now);
        List<TicketEvent> events = List.of(event(ticket, TicketEventType.STATUS_CHANGED, actor,
                ticket.status().name(), status.name(), now));

        TicketWriteResult result = apply(TicketMutation.of(updated, revisionOf(ticket, expectedRevision), null, events));
        if (!result.ok()) {
            return translate(result, ticketKey);
        }
        audit(actor, result.ticket(), "status", Map.of("oldStatus", ticket.status().name(), "newStatus", status.name()));
        return TicketOutcome.ok(result.ticket());
    }

    public TicketOutcome resolve(TicketActor actor, String ticketKey, Long expectedRevision) {
        if (!enabled()) {
            return TicketOutcome.fail(TicketError.TICKETS_DISABLED);
        }
        Ticket ticket = load(ticketKey);
        if (ticket == null) {
            return TicketOutcome.fail(TicketError.TICKET_NOT_FOUND);
        }
        if (!actor.has(ParadigmPermissions.TICKET_STAFF_RESOLVE) || !canHandle(ticket, actor)) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED, ticket);
        }
        if (ticket.status() == TicketStatus.CLOSED) {
            return TicketOutcome.fail(TicketError.TICKET_CLOSED, ticket);
        }
        long now = System.currentTimeMillis();
        Ticket updated = TicketWorkflow.applyStatus(ticket, TicketStatus.RESOLVED, now);
        List<TicketEvent> events = List.of(event(ticket, TicketEventType.RESOLVED, actor,
                ticket.status().name(), TicketStatus.RESOLVED.name(), now));

        TicketWriteResult result = apply(TicketMutation.of(updated, revisionOf(ticket, expectedRevision), null, events));
        if (!result.ok()) {
            return translate(result, ticketKey);
        }
        audit(actor, result.ticket(), "resolve", Map.of("oldStatus", ticket.status().name(),
                "newStatus", TicketStatus.RESOLVED.name()));
        publish(bus -> bus.ticketResolved(result.ticket(), events.get(0)));
        notify(target -> target.ticketResolved(result.ticket(), events.get(0)));
        return TicketOutcome.ok(result.ticket());
    }

    public TicketOutcome close(TicketActor actor, String ticketKey, Long expectedRevision) {
        if (!enabled()) {
            return TicketOutcome.fail(TicketError.TICKETS_DISABLED);
        }
        Ticket ticket = load(ticketKey);
        if (ticket == null) {
            return TicketOutcome.fail(TicketError.TICKET_NOT_FOUND);
        }
        boolean staff = actor.has(ParadigmPermissions.TICKET_STAFF_CLOSE) && canHandle(ticket, actor);
        boolean creator = ticket.isCreatedBy(actor.uuid()) && actor.has(ParadigmPermissions.TICKET_CLOSE);
        if (!staff && !creator) {
            return TicketOutcome.fail(TicketError.PERMISSION_DENIED, ticket);
        }
        if (ticket.status() == TicketStatus.CLOSED) {
            return TicketOutcome.fail(TicketError.TICKET_CLOSED, ticket);
        }
        long now = System.currentTimeMillis();
        Ticket updated = TicketWorkflow.applyStatus(ticket, TicketStatus.CLOSED, now);
        List<TicketEvent> events = List.of(event(ticket, TicketEventType.CLOSED, actor,
                ticket.status().name(), TicketStatus.CLOSED.name(), now));

        TicketWriteResult result = apply(TicketMutation.of(updated, revisionOf(ticket, expectedRevision), null, events));
        if (!result.ok()) {
            return translate(result, ticketKey);
        }
        if (staff && !creator) {
            audit(actor, result.ticket(), "close", Map.of("oldStatus", ticket.status().name(),
                    "newStatus", TicketStatus.CLOSED.name()));
        }
        publish(bus -> bus.ticketClosed(result.ticket(), events.get(0)));
        notify(target -> target.ticketClosed(result.ticket(), events.get(0)));
        return TicketOutcome.ok(result.ticket());
    }

    public TicketOutcome reopen(TicketActor actor, String ticketKey, Long expectedRevision) {
        if (!enabled()) {
            return TicketOutcome.fail(TicketError.TICKETS_DISABLED);
        }
        Ticket ticket = load(ticketKey);
        if (ticket == null) {
            return TicketOutcome.fail(TicketError.TICKET_NOT_FOUND);
        }
        if (!TicketWorkflow.isReopenable(ticket)) {
            return TicketOutcome.fail(TicketError.INVALID_STATUS_TRANSITION, ticket);
        }
        boolean staff = actor.has(ParadigmPermissions.TICKET_STAFF_REOPEN) && canHandle(ticket, actor);
        long now = System.currentTimeMillis();
        if (!staff) {
            boolean creator = ticket.isCreatedBy(actor.uuid()) && actor.has(ParadigmPermissions.TICKET_REOPEN);
            if (!creator || ticket.status() == TicketStatus.CLOSED) {
                return TicketOutcome.fail(TicketError.PERMISSION_DENIED, ticket);
            }
            TicketsConfigHandler.Config config = config();
            int window = ConfigEntry.valueOf(config != null ? config.playerReopenWindowHours : null, 48);
            if (!TicketWorkflow.playerMayReopen(ticket, now, window)) {
                return TicketOutcome.fail(TicketError.REOPEN_WINDOW_EXPIRED, ticket);
            }
        }
        TicketStatus nextStatus = TicketWorkflow.statusAfterReopen(ticket);
        Ticket updated = TicketWorkflow.applyStatus(ticket, nextStatus, now);
        List<TicketEvent> events = List.of(event(ticket, TicketEventType.REOPENED, actor,
                ticket.status().name(), nextStatus.name(), now));

        TicketWriteResult result = apply(TicketMutation.of(updated, revisionOf(ticket, expectedRevision), null, events));
        if (!result.ok()) {
            return translate(result, ticketKey);
        }
        if (staff) {
            audit(actor, result.ticket(), "reopen", Map.of("oldStatus", ticket.status().name(),
                    "newStatus", nextStatus.name()));
        }
        publish(bus -> bus.ticketReopened(result.ticket(), events.get(0)));
        notify(target -> target.ticketReopened(result.ticket(), events.get(0)));
        return TicketOutcome.ok(result.ticket());
    }

    public Optional<Ticket> find(TicketActor actor, String ticketKey) {
        Ticket ticket = load(ticketKey);
        if (ticket == null) {
            return Optional.empty();
        }
        return mayView(actor, ticket) ? Optional.of(ticket) : Optional.empty();
    }

    public TicketPage list(TicketQuery query) {
        TicketRepository repository = repository();
        if (repository == null) {
            return TicketPage.empty(query.page(), query.pageSize());
        }
        return new TicketPage(repository.listTickets(query), repository.countTickets(query),
                query.page(), query.pageSize());
    }

    public record VisiblePage(TicketPage page, Map<String, Integer> summary) {
    }

    public VisiblePage listVisible(TicketActor actor, TicketQuery requested) {
        List<Ticket> visible = new ArrayList<>();
        Map<String, Integer> summary = new LinkedHashMap<>();
        int sourcePage = 1;
        int sourceTotal;
        do {
            TicketQuery scan = new TicketQuery(requested.networkId(), requested.statuses(), requested.priorities(),
                    requested.category(), requested.originServerId(), requested.assigneeUuid(), requested.unassignedOnly(),
                    requested.creatorUuid(), requested.search(), sourcePage, TicketQuery.MAX_PAGE_SIZE);
            TicketPage raw = list(scan);
            sourceTotal = raw.total();
            for (Ticket ticket : raw.tickets()) {
                if (mayView(actor, ticket)) {
                    visible.add(ticket);
                    TicketSummaries.accumulate(summary, ticket.status(), ticket.priority(), !ticket.isAssigned());
                }
            }
            sourcePage++;
        } while ((sourcePage - 1) * TicketQuery.MAX_PAGE_SIZE < sourceTotal);

        int from = Math.min((requested.page() - 1) * requested.pageSize(), visible.size());
        int to = Math.min(from + requested.pageSize(), visible.size());
        TicketPage page = new TicketPage(visible.subList(from, to), visible.size(), requested.page(), requested.pageSize());
        return new VisiblePage(page, TicketSummaries.finish(summary));
    }

    public TicketPage listOwn(TicketActor actor, int page, int pageSize) {
        return list(TicketQuery.builder(networkId())
                .creatorUuid(actor.uuid())
                .page(page)
                .pageSize(pageSize)
                .build());
    }

    public Map<String, Integer> summary() {
        TicketRepository repository = repository();
        return repository != null ? repository.summaryCounts(networkId()) : TicketSummaries.empty();
    }

    public List<TicketMessage> messages(TicketActor actor, String ticketKey, int offset, int limit) {
        Ticket ticket = load(ticketKey);
        if (ticket == null || !mayView(actor, ticket)) {
            return List.of();
        }
        TicketRepository repository = repository();
        return repository != null
                ? repository.listMessages(ticket.networkId(), ticket.ticketKey(), offset, limit)
                : List.of();
    }

    public List<TicketEvent> history(TicketActor actor, String ticketKey) {
        Ticket ticket = load(ticketKey);
        if (ticket == null || !mayView(actor, ticket)) {
            return List.of();
        }
        TicketRepository repository = repository();
        return repository != null ? repository.listEvents(ticket.networkId(), ticket.ticketKey()) : List.of();
    }

    public boolean mayView(TicketActor actor, Ticket ticket) {
        if (ticket == null || actor == null) {
            return false;
        }
        if (actor.has(ParadigmPermissions.TICKET_STAFF_VIEW) && canHandle(ticket, actor)) {
            return true;
        }
        return ticket.isCreatedBy(actor.uuid()) && actor.has(ParadigmPermissions.TICKET_VIEW);
    }

    public boolean isStaff(TicketActor actor) {
        return actor != null && actor.has(ParadigmPermissions.TICKET_STAFF_VIEW);
    }

    public int autoCloseSweep() {
        TicketsConfigHandler.Config config = config();
        TicketRepository repository = repository();
        if (!enabled(config) || repository == null) {
            return 0;
        }
        int resolvedHours = ConfigEntry.valueOf(config.autoCloseResolvedAfterHours, 0);
        int waitingDays = ConfigEntry.valueOf(config.autoCloseWaitingPlayerAfterDays, 0);
        if (resolvedHours <= 0 && waitingDays <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        Long resolvedBefore = resolvedHours > 0 ? now - java.util.concurrent.TimeUnit.HOURS.toMillis(resolvedHours) : null;
        Long waitingBefore = waitingDays > 0 ? now - java.util.concurrent.TimeUnit.DAYS.toMillis(waitingDays) : null;

        int closed = 0;
        for (Ticket ticket : repository.findAutoCloseCandidates(networkId(), resolvedBefore, waitingBefore, 100)) {
            Ticket updated = TicketWorkflow.applyStatus(ticket, TicketStatus.CLOSED, now);
            TicketEvent autoEvent = new TicketEvent(TicketIds.eventId(), ticket.ticketId(), ticket.networkId(),
                    ticket.ticketKey(), TicketEventType.CLOSED, null, "Paradigm", serverId(),
                    ticket.status().name(), TicketStatus.CLOSED.name(), now);
            TicketWriteResult result = repository.applyMutation(
                    TicketMutation.of(updated, ticket.revision(), null, List.of(autoEvent)));
            if (result.ok()) {
                closed++;
                publish(bus -> bus.ticketClosed(result.ticket(), autoEvent));
                notify(target -> target.ticketClosed(result.ticket(), autoEvent));
            }
        }
        return closed;
    }

    /**
     * Resolves assignment identities strictly through Paradigm's storage layer.
     * This method is intentionally safe for dashboard/scheduler worker threads
     * and does not read Minecraft's live player list.
     */
    public Optional<TicketIdentity> resolveIdentity(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) {
            return Optional.empty();
        }
        String needle = nameOrUuid.trim();
        StorageService storage = services != null ? services.getStorageService() : null;
        if (storage == null) {
            return Optional.empty();
        }
        try {
            if (looksLikeUuid(needle)) {
                Optional<StoredPlayerProfile> byUuid = storage.players().getProfile(needle);
                if (byUuid.isPresent()) {
                    return Optional.of(new TicketIdentity(byUuid.get().uuid(), byUuid.get().name(), false));
                }
            }
            for (StoredPlayerProfile profile : storage.players().listProfiles()) {
                if (profile.name() != null && profile.name().equalsIgnoreCase(needle)) {
                    return Optional.of(new TicketIdentity(profile.uuid(), profile.name(), false));
                }
            }
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean looksLikeUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public String networkId() {
        StorageService storage = services != null ? services.getStorageService() : null;
        if (storage != null && storage.context() != null) {
            return storage.context().networkId();
        }
        return "default";
    }

    public String serverId() {
        StorageService storage = services != null ? services.getStorageService() : null;
        if (storage != null && storage.context() != null) {
            return storage.context().serverId();
        }
        return "default";
    }

    public TicketRepository repository() {
        StorageService storage = services != null ? services.getStorageService() : null;
        try {
            return storage != null ? storage.tickets() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public TicketsConfigHandler.Config config() {
        return TicketsConfigHandler.configOrNull();
    }

    public boolean enabled() {
        return enabled(config());
    }

    private static boolean enabled(TicketsConfigHandler.Config config) {
        return config != null && Boolean.TRUE.equals(config.enabled.value);
    }

    private Ticket load(String ticketKey) {
        String normalized = TicketIds.normalizeKey(ticketKey);
        TicketRepository repository = repository();
        if (normalized == null || repository == null) {
            return null;
        }
        return repository.findTicket(networkId(), normalized).orElse(null);
    }

    private TicketWriteResult apply(TicketMutation mutation) {
        TicketRepository repository = repository();
        if (repository == null) {
            return TicketWriteResult.unsupported();
        }
        return repository.applyMutation(mutation);
    }

    private static long revisionOf(Ticket ticket, Long expectedRevision) {
        return expectedRevision != null ? expectedRevision : ticket.revision();
    }

    private List<TicketEvent> eventsWithStatusChange(Ticket ticket, TicketEventType type, TicketActor actor,
                                                     String oldValue, String newValue,
                                                     TicketStatus nextStatus, long nowMs) {
        List<TicketEvent> events = new ArrayList<>();
        events.add(event(ticket, type, actor, oldValue, newValue, nowMs));
        if (nextStatus != ticket.status()) {
            events.add(event(ticket, TicketEventType.STATUS_CHANGED, actor,
                    ticket.status().name(), nextStatus.name(), nowMs));
        }
        return events;
    }

    private TicketEvent event(Ticket ticket, TicketEventType type, TicketActor actor,
                              String oldValue, String newValue, long nowMs) {
        return new TicketEvent(TicketIds.eventId(), ticket.ticketId(), ticket.networkId(), ticket.ticketKey(),
                type, actor != null ? actor.uuid() : null, actor != null ? actor.name() : null,
                serverId(), oldValue, newValue, nowMs);
    }

    private boolean canHandle(Ticket ticket, TicketActor actor) {
        TicketsConfigHandler.Config config = config();
        if (config == null) {
            return true;
        }
        return TicketCategories.mayStaffHandle(config.category(ticket.category()), actor);
    }

    private boolean mayUseUrgent(TicketActor actor) {
        TicketsConfigHandler.Config config = config();
        if (config != null && Boolean.TRUE.equals(config.allowPlayerUrgentPriority.value)) {
            return true;
        }
        return actor.has(ParadigmPermissions.TICKET_PRIORITY_URGENT);
    }

    private static TicketOutcome translate(TicketWriteResult result, String ticketKey) {
        return switch (result.status()) {
            case NOT_FOUND -> TicketOutcome.fail(TicketError.TICKET_NOT_FOUND, null,
                    Map.of("ticket", String.valueOf(ticketKey)));
            case STALE_REVISION -> TicketOutcome.fail(TicketError.STALE_TICKET, result.ticket(),
                    Map.of("ticket", String.valueOf(ticketKey)));
            case ALREADY_CLAIMED -> TicketOutcome.fail(TicketError.ALREADY_CLAIMED, result.ticket(),
                    Map.of("ticket", String.valueOf(ticketKey),
                            "actor", result.ticket() != null ? String.valueOf(result.ticket().assigneeName()) : ""));
            case LIMIT_REACHED -> TicketOutcome.fail(TicketError.TICKET_LIMIT_REACHED);
            case COOLDOWN -> TicketOutcome.fail(TicketError.TICKET_COOLDOWN);
            case UNSUPPORTED -> TicketOutcome.fail(TicketError.STORAGE_UNAVAILABLE);
            case OK -> TicketOutcome.ok(result.ticket());
        };
    }

    private void audit(TicketActor actor, Ticket ticket, String action, Map<String, String> extra) {
        if (services == null || ticket == null) {
            return;
        }
        try {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("ticket", ticket.ticketKey());
            details.put("action", action);
            details.put("category", ticket.category());
            details.put("targetUuid", String.valueOf(ticket.creatorUuid()));
            details.put("targetName", String.valueOf(ticket.creatorName()));
            details.put("networkId", String.valueOf(ticket.networkId()));
            details.put("serverId", serverId());
            if (extra != null) {
                details.putAll(extra);
            }
            services.getAuditService().dashboard(
                    new eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal(
                            actor != null ? actor.uuid() : null, actor != null ? actor.name() : "system", false),
                    AuditActionType.TICKET_CHANGE, AuditResult.SUCCESS,
                    "Ticket " + action + " on " + ticket.ticketKey() + ".", details);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void publish(Consumer<ParadigmEvents> action) {
        if (services == null) {
            return;
        }
        try {
            action.accept(services.getParadigmEvents());
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void notify(Consumer<TicketNotifier> action) {
        TicketNotifier current = notifier;
        if (current == null) {
            return;
        }
        try {
            action.accept(current);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    public record TicketIdentity(String uuid, String name, boolean online) {
    }
}
