package eu.avalanche7.paradigm.modules.tickets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import eu.avalanche7.paradigm.configs.ConfigEntry;
import eu.avalanche7.paradigm.configs.TicketsConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.core.network.NetworkEventBus;
import eu.avalanche7.paradigm.core.network.SqlPollingNetworkEventBus;
import eu.avalanche7.paradigm.modules.commands.shared.CommandMessages;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public class TicketsModule implements ParadigmModule {

    private static final String HEADER = "Tickets";
    private static final PermissionDefinition[] TICKET_PERMISSIONS = {
            ParadigmPermissions.TICKET_CREATE,
            ParadigmPermissions.TICKET_VIEW,
            ParadigmPermissions.TICKET_REPLY,
            ParadigmPermissions.TICKET_CLOSE,
            ParadigmPermissions.TICKET_REOPEN,
            ParadigmPermissions.TICKET_PRIORITY_URGENT,
            ParadigmPermissions.TICKET_STAFF_VIEW,
            ParadigmPermissions.TICKET_STAFF_REPLY,
            ParadigmPermissions.TICKET_STAFF_CLAIM,
            ParadigmPermissions.TICKET_STAFF_ASSIGN,
            ParadigmPermissions.TICKET_STAFF_PRIORITY,
            ParadigmPermissions.TICKET_STAFF_STATUS,
            ParadigmPermissions.TICKET_STAFF_RESOLVE,
            ParadigmPermissions.TICKET_STAFF_CLOSE,
            ParadigmPermissions.TICKET_STAFF_REOPEN,
            ParadigmPermissions.TICKET_MANAGE
    };
    private static volatile TicketsModule current;

    private Services services;
    private TicketService tickets;
    private TicketChatView view;
    private TicketNotifier notifier;
    private NetworkEventBus networkEvents;
    private volatile ScheduledFuture<?> autoCloseTask;
    private final ThreadLocal<TicketActor> commandActor = new ThreadLocal<>();

    public static TicketsModule current() {
        return current;
    }

    @Override
    public String getName() {
        return "Tickets";
    }

    @Override
    public boolean isEnabled(Services services) {
        TicketsConfigHandler.Config config = TicketsConfigHandler.configOrNull();
        return config == null || Boolean.TRUE.equals(config.enabled.value);
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.tickets = services.getTicketService();
        this.notifier = new TicketNotifier(services);
        this.view = new TicketChatView(services);
        this.networkEvents = new SqlPollingNetworkEventBus(services, this::crossServerPollSeconds);
        TicketNetworkEvents.install(networkEvents, services, tickets, notifier);
        this.tickets.setNotifier(notifier);
        current = this;
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        this.services = services;
        if (isEnabled(services)) {
            startTasks();
        }
    }

    @Override
    public void onEnable(Services services) {
        this.services = services;
        if (this.tickets == null) {
            onLoad(null, services, null);
        }
        this.tickets.setNotifier(notifier);
        startTasks();
    }

    @Override
    public void onDisable(Services services) {
        stopTasks();
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        stopTasks();
    }

    public void reloadTasks() {
        if (services == null) {
            return;
        }
        if (isEnabled(services)) {
            startTasks();
        } else {
            stopTasks();
        }
    }

    private synchronized void startTasks() {
        if (services == null) {
            return;
        }
        stopTasks();
        TicketsConfigHandler.Config config = TicketsConfigHandler.configOrNull();
        if (config != null && services.getTaskScheduler() != null) {
            int resolvedHours = ConfigEntry.valueOf(config.autoCloseResolvedAfterHours, 0);
            int waitingDays = ConfigEntry.valueOf(config.autoCloseWaitingPlayerAfterDays, 0);
            if (resolvedHours > 0 || waitingDays > 0) {
                int intervalMinutes = Math.max(1, ConfigEntry.valueOf(config.autoCloseSweepIntervalMinutes, 30));
                autoCloseTask = services.getTaskScheduler().scheduleAtFixedRateRaw(
                        this::runAutoCloseSweep, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
            }
        }
        if (networkEvents != null) {
            networkEvents.start();
        }
    }

    private synchronized void stopTasks() {
        ScheduledFuture<?> task = autoCloseTask;
        autoCloseTask = null;
        if (task != null) {
            task.cancel(false);
        }
        if (networkEvents != null) {
            networkEvents.stop();
        }
    }

    private void runAutoCloseSweep() {
        try {
            int closed = tickets.autoCloseSweep();
            if (closed > 0 && services != null && services.getDebugLogger() != null) {
                services.getDebugLogger().debugLog("Tickets: auto-closed " + closed + " ticket(s).");
            }
        } catch (RuntimeException | LinkageError failure) {
            if (services != null && services.getLogger() != null) {
                services.getLogger().warn("Paradigm tickets: auto-close sweep failed: {}", failure.getMessage());
            }
        }
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        if (this.tickets == null) {
            onLoad(null, services, null);
        }
        services.getPlatformAdapter().registerCommand(buildTicketCommand());
        services.getPlatformAdapter().registerCommand(buildTicketsCommand());
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        this.services = services;
    }

    private ICommandBuilder builder() {
        return services.getPlatformAdapter().createCommandBuilder();
    }

    private ICommandBuilder buildTicketCommand() {
        ICommandBuilder root = builder().literal("ticket")
                .requires(source -> toggled("ticket"))
                .executes(context -> executeAsync(this::listOwn, context));

        root.then(builder().literal("create")
                .requires(source -> allowed(source, ParadigmPermissions.TICKET_CREATE))
                .then(builder().argument("category", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> categorySuggestions(context))
                        .then(builder().argument("message", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(context -> executeAsync(
                                        ctx -> create(ctx, ctx.getStringArgument("category")), context))))
                .then(builder().argument("message", ICommandBuilder.ArgumentType.GREEDY_STRING)
                        .executes(context -> executeAsync(ctx -> create(ctx, null), context))));

        root.then(builder().literal("list")
                .requires(source -> allowed(source, ParadigmPermissions.TICKET_VIEW))
                .executes(context -> executeAsync(this::listOwn, context))
                .then(builder().argument("page", ICommandBuilder.ArgumentType.INTEGER)
                        .executes(context -> executeAsync(this::listOwn, context))));

        root.then(builder().literal("view")
                .then(builder().argument("id", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> ticketSuggestions(context))
                        .executes(context -> executeAsync(this::viewTicket, context))));

        root.then(builder().literal("reply")
                .then(builder().argument("id", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> ticketSuggestions(context))
                        .then(builder().argument("message", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(context -> executeAsync(this::reply, context)))));

        root.then(simpleAction("close", this::close));
        root.then(simpleAction("reopen", this::reopen));
        root.then(simpleAction("claim", this::claim));
        root.then(simpleAction("unclaim", this::unclaim));
        root.then(simpleAction("resolve", this::resolve));

        root.then(builder().literal("assign")
                .requires(source -> allowed(source, ParadigmPermissions.TICKET_STAFF_ASSIGN))
                .then(builder().argument("id", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> ticketSuggestions(context))
                        .then(builder().argument("staff", ICommandBuilder.ArgumentType.WORD)
                                .suggests((context, input) -> services.getPlatformAdapter().getOnlinePlayerNames())
                                .executes(context -> executeAsync(this::assign, context)))));

        root.then(builder().literal("priority")
                .then(builder().argument("id", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> ticketSuggestions(context))
                        .then(builder().argument("priority", ICommandBuilder.ArgumentType.WORD)
                                .suggests(List.of("low", "normal", "high", "urgent"))
                                .executes(context -> executeAsync(this::changePriority, context)))));

        root.then(builder().literal("status")
                .requires(source -> allowed(source, ParadigmPermissions.TICKET_STAFF_STATUS))
                .then(builder().argument("id", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> ticketSuggestions(context))
                        .then(builder().argument("status", ICommandBuilder.ArgumentType.WORD)
                                .suggests(statusSuggestions())
                                .executes(context -> executeAsync(this::changeStatus, context)))));

        root.then(builder().literal("category")
                .requires(source -> allowed(source, ParadigmPermissions.TICKET_STAFF_STATUS))
                .then(builder().argument("id", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> ticketSuggestions(context))
                        .then(builder().argument("category", ICommandBuilder.ArgumentType.WORD)
                                .suggests((context, input) -> categorySuggestions(context))
                                .executes(context -> executeAsync(this::changeCategory, context)))));

        return root;
    }

    private ICommandBuilder buildTicketsCommand() {
        return builder().literal("tickets")
                .requires(source -> toggled("tickets") && allowed(source, ParadigmPermissions.TICKET_STAFF_VIEW))
                .executes(context -> executeAsync(this::listQueue, context))
                .then(builder().argument("page", ICommandBuilder.ArgumentType.INTEGER)
                        .executes(context -> executeAsync(this::listQueue, context)));
    }

    private ICommandBuilder simpleAction(String literal, ICommandBuilder.CommandExecutor executor) {
        return builder().literal(literal)
                .then(builder().argument("id", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> ticketSuggestions(context))
                        .executes(context -> executeAsync(executor, context)));
    }

    /**
     * Ticket repositories may be JDBC backed. Run service/repository work on the
     * raw scheduler and use a main-thread permission snapshot so no native
     * player permission state is queried from the worker.
     */
    private int executeAsync(ICommandBuilder.CommandExecutor executor, ICommandContext context) {
        TicketActor snapshot = snapshotActor(context);
        if (snapshot == null) {
            return playersOnly(context);
        }
        if (services == null || services.getTaskScheduler() == null) {
            return commandUnavailable(context.getSource());
        }
        services.getTaskScheduler().scheduleRaw(() -> {
            commandActor.set(snapshot);
            try {
                executor.execute(context);
            } catch (Throwable failure) {
                if (services.getLogger() != null) {
                    services.getLogger().error("Paradigm ticket command failed: input={}", context.getInput(), failure);
                }
                commandFailure(context.getSource());
            } finally {
                commandActor.remove();
            }
        }, 0L, TimeUnit.MILLISECONDS);
        return 1;
    }

    private TicketActor snapshotActor(ICommandContext context) {
        IPlayer player = context != null && context.getSource() != null ? context.getSource().getPlayer() : null;
        if (player == null || services == null || services.getPermissionsHandler() == null) {
            return null;
        }
        Map<String, Boolean> permissions = new HashMap<>();
        for (PermissionDefinition permission : TICKET_PERMISSIONS) {
            permissions.put(permission.node(), services.getPermissionsHandler().hasPermission(player, permission));
        }
        TicketsConfigHandler.Config config = TicketsConfigHandler.configOrNull();
        for (TicketsConfigHandler.CategoryEntry category : TicketCategories.enabled(config)) {
            snapshotNode(permissions, player, category.permission);
            snapshotNode(permissions, player, category.staffPermission);
        }
        String uuid = player.getUUID();
        String name = player.getName();
        return TicketActor.administrative(uuid, name,
                permission -> permission != null && permissions.getOrDefault(permission.node(), false));
    }

    private void snapshotNode(Map<String, Boolean> permissions, IPlayer player, String node) {
        if (node == null || node.isBlank() || permissions.containsKey(node)) {
            return;
        }
        permissions.put(node, services.getPermissionsHandler().hasPermission(
                player, node, PermissionDefinition.NO_VANILLA_FALLBACK));
    }

    private int create(ICommandContext context, String category) {
        TicketActor actor = actor(context);
        if (actor == null) {
            return playersOnly(context);
        }
        String message = context.getStringArgument("message");
        String resolvedCategory = category;
        if (category != null && !isKnownCategory(category)) {
            message = category + " " + message;
            resolvedCategory = null;
        }
        TicketOutcome outcome = tickets.create(actor, resolvedCategory, message);
        if (!outcome.ok()) {
            return failure(context, outcome);
        }
        view.sendCreated(context.getSource(), outcome.ticket());
        return 1;
    }

    private int listOwn(ICommandContext context) {
        TicketActor actor = actor(context);
        if (actor == null) {
            return playersOnly(context);
        }
        int page = optionalPage(context);
        TicketPage result = tickets.listOwn(actor, page, pageSize());
        view.sendList(context.getSource(), actor, result, false);
        return 1;
    }

    private int listQueue(ICommandContext context) {
        TicketActor actor = actor(context);
        if (actor == null) {
            return playersOnly(context);
        }
        if (!tickets.isStaff(actor)) {
            return failure(context, TicketOutcome.fail(TicketError.PERMISSION_DENIED));
        }
        int page = optionalPage(context);
        TicketQuery requested = TicketQuery.builder(tickets.networkId())
                .statuses(List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS,
                        TicketStatus.WAITING_STAFF, TicketStatus.WAITING_PLAYER))
                .page(page)
                .pageSize(pageSize())
                .build();
        TicketPage result = tickets.listVisible(actor, requested).page();
        view.sendList(context.getSource(), actor, result, true);
        return 1;
    }

    private int viewTicket(ICommandContext context) {
        TicketActor actor = actor(context);
        if (actor == null) {
            return playersOnly(context);
        }
        String key = context.getStringArgument("id");
        Optional<Ticket> found = tickets.find(actor, key);
        if (found.isEmpty()) {
            return failure(context, TicketOutcome.fail(TicketError.TICKET_NOT_FOUND), key);
        }
        Ticket ticket = found.get();
        List<TicketMessage> all = tickets.messages(actor, key, 0, 500);
        int preview = previewCount();
        List<TicketMessage> recent = all.size() <= preview ? all : all.subList(all.size() - preview, all.size());
        view.sendTicket(context.getSource(), actor, ticket, recent, tickets.isStaff(actor));
        return 1;
    }

    private int reply(ICommandContext context) {
        return mutate(context, (actor, key) ->
                tickets.reply(actor, key, context.getStringArgument("message"), null), "tickets.replied",
                "Reply added to {ticket}.");
    }

    private int close(ICommandContext context) {
        return mutate(context, (actor, key) -> tickets.close(actor, key, null), "tickets.closed",
                "Ticket {ticket} closed.");
    }

    private int reopen(ICommandContext context) {
        return mutate(context, (actor, key) -> tickets.reopen(actor, key, null), "tickets.reopened",
                "Ticket {ticket} reopened.");
    }

    private int claim(ICommandContext context) {
        return mutate(context, (actor, key) -> tickets.claim(actor, key, null), "tickets.claimed",
                "You claimed ticket {ticket}.");
    }

    private int unclaim(ICommandContext context) {
        return mutate(context, (actor, key) -> tickets.unclaim(actor, key, null), "tickets.unclaimed",
                "Ticket {ticket} is no longer assigned.");
    }

    private int resolve(ICommandContext context) {
        return mutate(context, (actor, key) -> tickets.resolve(actor, key, null), "tickets.resolved",
                "Ticket {ticket} marked resolved.");
    }

    private int assign(ICommandContext context) {
        String target = context.getStringArgument("staff");
        return mutate(context, (actor, key) -> tickets.assign(actor, key, target, null), "tickets.assigned",
                "Ticket {ticket} assigned to {target}.", "{target}", target);
    }

    private int changePriority(ICommandContext context) {
        Optional<TicketPriority> priority = TicketPriority.parse(context.getStringArgument("priority"));
        if (priority.isEmpty()) {
            return failure(context, TicketOutcome.fail(TicketError.INVALID_PRIORITY));
        }
        return mutate(context, (actor, key) -> tickets.changePriority(actor, key, priority.get(), null),
                "tickets.priority_changed", "Ticket {ticket} priority is now {value}.",
                "{value}", priority.get().name());
    }

    private int changeStatus(ICommandContext context) {
        Optional<TicketStatus> status = TicketStatus.parse(context.getStringArgument("status"));
        if (status.isEmpty()) {
            return failure(context, TicketOutcome.fail(TicketError.INVALID_STATUS));
        }
        return mutate(context, (actor, key) -> tickets.changeStatus(actor, key, status.get(), null),
                "tickets.status_changed", "Ticket {ticket} status is now {value}.",
                "{value}", status.get().name());
    }

    private int changeCategory(ICommandContext context) {
        String category = context.getStringArgument("category");
        return mutate(context, (actor, key) -> tickets.changeCategory(actor, key, category, null),
                "tickets.category_changed", "Ticket {ticket} category is now {value}.",
                "{value}", category);
    }

    private int mutate(ICommandContext context, TicketAction action, String key, String fallback,
                       String... extraPlaceholders) {
        TicketActor actor = actor(context);
        if (actor == null) {
            return playersOnly(context);
        }
        String ticketKey = context.getStringArgument("id");
        TicketOutcome outcome = action.run(actor, ticketKey);
        if (!outcome.ok()) {
            return failure(context, outcome, ticketKey);
        }
        List<String> placeholders = new ArrayList<>(List.of("{ticket}", outcome.ticket().ticketKey()));
        placeholders.addAll(List.of(extraPlaceholders));
        commandMessage(context.getSource(), key, fallback, placeholders.toArray(String[]::new));
        return 1;
    }

    private int failure(ICommandContext context, TicketOutcome outcome) {
        return failure(context, outcome, null);
    }

    private int failure(ICommandContext context, TicketOutcome outcome, String requestedKey) {
        TicketError error = outcome.error();
        List<String> placeholders = new ArrayList<>();
        placeholders.add("{ticket}");
        placeholders.add(resolveTicketLabel(outcome, requestedKey));
        placeholders.add("{limit}");
        placeholders.add(String.valueOf(outcome.detail("limit")));
        placeholders.add("{actor}");
        placeholders.add(String.valueOf(outcome.detail("actor")));
        placeholders.add("{duration}");
        placeholders.add(durationDetail(outcome));
        commandMessage(context.getSource(), error.langKey(), error.fallback(), placeholders.toArray(String[]::new));
        return 0;
    }

    private void commandMessage(ICommandSource source, String key, String fallback, String... placeholders) {
        if (services == null || services.getPlatformAdapter() == null) {
            return;
        }
        String[] snapshot = placeholders != null ? placeholders.clone() : new String[0];
        services.getPlatformAdapter().executeOnServerThread(() ->
                CommandMessages.source(services, source, HEADER, key, fallback, snapshot));
    }

    private void commandFailure(ICommandSource source) {
        if (services == null || services.getPlatformAdapter() == null) {
            return;
        }
        services.getPlatformAdapter().executeOnServerThread(() -> {
            String message = "Command failed. Check the server log for details.";
            if (services.getLang() != null) {
                String translated = services.getLang().getTranslation("command.execution_failed");
                if (translated != null && !translated.equals("command.execution_failed")) {
                    message = translated;
                }
            }
            services.getPlatformAdapter().sendFailure(source, services.getPlatformAdapter().createLiteralComponent(message));
        });
    }

    private int commandUnavailable(ICommandSource source) {
        commandMessage(source, "command.execution_failed", "Command failed. Check the server log for details.");
        return 0;
    }

    private static String resolveTicketLabel(TicketOutcome outcome, String requestedKey) {
        if (outcome.ticket() != null) {
            return outcome.ticket().ticketKey();
        }
        String detail = outcome.detail("ticket");
        if (detail != null && !detail.isBlank() && !"null".equals(detail)) {
            return detail;
        }
        String normalized = TicketIds.normalizeKey(requestedKey);
        if (normalized != null) {
            return normalized;
        }
        return requestedKey != null && !requestedKey.isBlank() ? requestedKey : "?";
    }

    private static String durationDetail(TicketOutcome outcome) {
        String raw = outcome.detail("remainingMs");
        if (raw == null) {
            return "";
        }
        try {
            return eu.avalanche7.paradigm.utils.DurationFormatter.humanize(Long.parseLong(raw));
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private int playersOnly(ICommandContext context) {
        commandMessage(context != null ? context.getSource() : null, "tickets.players_only",
                "Only players can use ticket commands in chat. Use the dashboard instead.");
        return 0;
    }

    private TicketActor actor(ICommandContext context) {
        TicketActor snapshot = commandActor.get();
        if (snapshot != null) {
            return snapshot;
        }
        IPlayer player = context.getSource() != null ? context.getSource().getPlayer() : null;
        if (player == null) {
            return null;
        }
        return TicketActor.of(player, services.getPermissionsHandler());
    }

    /**
     * Ticket IDs are storage-backed. Doing a repository query from Brigadier's
     * suggestion path can stall the server/network thread, so IDs are deliberately
     * not queried here. Category/status/player suggestions remain cheap and native.
     */
    private List<String> ticketSuggestions(ICommandContext context) {
        return List.of();
    }

    private boolean isKnownCategory(String id) {
        return TicketCategories.resolve(TicketsConfigHandler.configOrNull(), id) != null;
    }

    private List<String> categorySuggestions(ICommandContext context) {
        TicketActor actor = actor(context);
        return TicketCategories.selectableIds(TicketsConfigHandler.configOrNull(), actor);
    }

    private static List<String> statusSuggestions() {
        List<String> values = new ArrayList<>();
        for (TicketStatus status : TicketStatus.values()) {
            values.add(status.name().toLowerCase(Locale.ROOT));
        }
        return values;
    }

    private int optionalPage(ICommandContext context) {
        try {
            return Math.max(1, context.getIntArgument("page"));
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    private int crossServerPollSeconds() {
        TicketsConfigHandler.Config config = TicketsConfigHandler.configOrNull();
        return ConfigEntry.valueOf(config != null ? config.crossServerNotifyPollSeconds : null, 5);
    }

    private int pageSize() {
        TicketsConfigHandler.Config config = TicketsConfigHandler.configOrNull();
        return Math.max(1, ConfigEntry.valueOf(config != null ? config.listPageSize : null, 8));
    }

    private int previewCount() {
        TicketsConfigHandler.Config config = TicketsConfigHandler.configOrNull();
        return Math.max(1, ConfigEntry.valueOf(config != null ? config.threadPreviewMessages : null, 5));
    }

    private boolean toggled(String commandId) {
        return services.getCommandToggleStore() == null || services.getCommandToggleStore().isEnabled(commandId);
    }

    private boolean allowed(ICommandSource source, PermissionDefinition permission) {
        if (source == null) {
            return false;
        }
        IPlayer player = source.getPlayer();
        if (player == null) {
            return source.isConsole() || source.hasPermissionLevel(permission.fallbackLevel());
        }
        return services.getPermissionsHandler().hasPermission(player, permission);
    }

    @FunctionalInterface
    private interface TicketAction {
        TicketOutcome run(TicketActor actor, String ticketKey);
    }
}
