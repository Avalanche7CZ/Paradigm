package eu.avalanche7.paradigm.modules.actions;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class PlayerInputService {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final int DEFAULT_TIMEOUT = 60;
    private static final int MAX_TIMEOUT = 3600;

    private final Services services;
    private final ConcurrentHashMap<UUID, InputSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    public PlayerInputService(Services services) {
        this.services = services;
    }

    public static String keyOf(CustomCommand.Action action) {
        String raw = action.getKey();
        String key = raw == null || raw.isBlank() ? "input" : raw.trim().toLowerCase(Locale.ROOT);
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("await_input key must use 1-32 lowercase letters, digits, or underscores, starting with a letter.");
        }
        return key;
    }

    public static int timeoutOf(CustomCommand.Action action) {
        int seconds = action.getTimeout() == null ? DEFAULT_TIMEOUT : action.getTimeout();
        if (seconds < 1 || seconds > MAX_TIMEOUT) {
            throw new IllegalArgumentException("await_input timeout must be between 1 and 3600 seconds.");
        }
        return seconds;
    }

    public static void validate(CustomCommand.Action action) {
        keyOf(action);
        timeoutOf(action);
        if (action.getText() == null || action.getText().isEmpty()
                || action.getText().stream().anyMatch(line -> line == null)
                || action.getText().stream().allMatch(String::isBlank)) {
            throw new IllegalArgumentException("await_input requires prompt text.");
        }
        if (action.getOnSuccess().isEmpty()) {
            throw new IllegalArgumentException("await_input requires at least one on_success action.");
        }
        if (action.getOnSuccess().stream().anyMatch(Objects::isNull)
                || action.getOnFailure().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("await_input continuations cannot contain null actions.");
        }
    }

    public static void validateActions(List<CustomCommand.Action> actions, String path, int depth) {
        if (actions == null) return;
        if (depth > ActionDispatcher.MAX_DEPTH) {
            throw new IllegalArgumentException(path + ": action nesting is too deep.");
        }
        for (int index = 0; index < actions.size(); index++) {
            CustomCommand.Action action = actions.get(index);
            if (action == null) continue;
            if ("await_input".equals(action.getType())) {
                try {
                    validate(action);
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(path + " action " + (index + 1) + ": " + invalid.getMessage());
                }
                if (index != actions.size() - 1) {
                    throw new IllegalArgumentException(path + ": await_input must be last; use on_success for subsequent actions.");
                }
            }
            validateActions(action.getOnSuccess(), path + " action " + (index + 1) + " on_success", depth + 1);
            validateActions(action.getOnFailure(), path + " action " + (index + 1) + " on_failure", depth + 1);
        }
    }

    public void start(CustomCommand.Action action, ActionContext context) {
        if (!active) {
            context.replyFailure("&cPlayer input is unavailable.");
            return;
        }
        IPlayer player = context.player();
        if (player == null) {
            throw new IllegalArgumentException("await_input requires a player.");
        }
        validate(action);
        UUID playerId = uuidOf(player);
        if (playerId == null) {
            throw new IllegalArgumentException("await_input requires a player UUID.");
        }
        String key = keyOf(action);
        if (context.values().containsKey(key)) {
            throw new IllegalArgumentException("await_input key '" + key + "' already exists in this action context.");
        }
        int timeoutSeconds = timeoutOf(action);
        services.getMenuService().closeForInput(player);
        InputSession session = new InputSession(playerId, UUID.randomUUID(), key,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds), context,
                List.copyOf(action.getOnSuccess()), List.copyOf(action.getOnFailure()));
        InputSession previous = sessions.put(playerId, session);
        if (previous != null) previous.cancelTimeout();
        session.timeout = services.getTaskScheduler().schedule(
                () -> timeout(session), timeoutSeconds, TimeUnit.SECONDS);
        if (session.timeout == null || session.timeout.isCancelled()) {
            sessions.remove(playerId, session);
            throw new IllegalStateException("Player input timeout could not be scheduled.");
        }
        for (String line : action.getText()) {
            context.reply(context.expand(line));
        }
        services.getPlatformAdapter().sendSystemMessage(player,
                services.getPlatformAdapter().createLiteralComponent("§7[Cancel]")
                        .onClickRunCommand("/paradigminput cancel " + session.token)
                        .onHoverText("Cancel this input prompt"));
    }

    public void onChat(IEventSystem.ChatEvent event) {
        if (!active || event == null) return;
        UUID playerId = uuidOf(event.getPlayer());
        if (playerId == null) return;
        InputSession session = sessions.remove(playerId);
        if (session == null) return;
        event.setCancelled(true);
        session.cancelTimeout();
        String message = event.getMessage();
        if (System.nanoTime() - session.deadlineNanos >= 0) {
            services.getPlatformAdapter().executeOnServerThread(() -> finishFailure(session, "&eInput timed out."));
            return;
        }
        services.getPlatformAdapter().executeOnServerThread(() -> {
            IPlayer live = services.getPlatformAdapter().getPlayerByUuid(playerId.toString());
            if (!active || live == null || !sameConnection(session, live)) return;
            ActionContext resumed = session.context.toBuilder()
                    .source(services.getPlatformAdapter().createCommandSourceForPlayer(live))
                    .player(live)
                    .literalValue(session.key, message)
                    .build();
            services.getActionDispatcher().execute(session.onSuccess, resumed);
        });
    }

    public boolean cancel(IPlayer player) {
        return cancel(player, null);
    }

    public boolean cancel(IPlayer player, UUID token) {
        UUID playerId = uuidOf(player);
        if (playerId == null) return false;
        InputSession session = sessions.get(playerId);
        if (session == null) return false;
        if (token != null && !session.token.equals(token)) return false;
        if (!sessions.remove(playerId, session)) return false;
        session.cancelTimeout();
        finishFailure(session, "&eInput cancelled.");
        return true;
    }

    public void disconnect(IPlayer player) {
        UUID playerId = uuidOf(player);
        if (playerId == null) return;
        InputSession session = sessions.remove(playerId);
        if (session != null) session.cancelTimeout();
    }

    public int pendingCount() {
        return sessions.size();
    }

    private static UUID uuidOf(IPlayer player) {
        if (player == null || player.getUUID() == null) return null;
        try {
            return UUID.fromString(player.getUUID());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public void activate() {
        active = true;
    }

    public void clear() {
        for (InputSession session : sessions.values()) session.cancelTimeout();
        sessions.clear();
    }

    public void shutdown() {
        active = false;
        clear();
    }

    private void timeout(InputSession session) {
        if (sessions.remove(session.playerId, session)) {
            finishFailure(session, "&eInput timed out.");
        }
    }

    private void finishFailure(InputSession session, String message) {
        if (!active) return;
        IPlayer player = services.getPlatformAdapter().getPlayerByUuid(session.playerId.toString());
        if (player == null || !sameConnection(session, player)) return;
        ActionContext context = session.context.toBuilder()
                .source(services.getPlatformAdapter().createCommandSourceForPlayer(player))
                .player(player).build();
        context.reply(message);
        services.getActionDispatcher().execute(session.onFailure, context);
    }

    private static boolean sameConnection(InputSession session, IPlayer live) {
        IPlayer original = session.context.player();
        if (original == null) return false;
        Object originalHandle = original.getOriginalPlayer();
        Object liveHandle = live.getOriginalPlayer();
        return originalHandle == liveHandle;
    }

    private static final class InputSession {
        private final UUID playerId;
        private final UUID token;
        private final String key;
        private final long deadlineNanos;
        private final ActionContext context;
        private final List<CustomCommand.Action> onSuccess;
        private final List<CustomCommand.Action> onFailure;
        private volatile ScheduledFuture<?> timeout;

        private InputSession(UUID playerId, UUID token, String key, long deadlineNanos, ActionContext context,
                List<CustomCommand.Action> onSuccess, List<CustomCommand.Action> onFailure) {
            this.playerId = playerId;
            this.token = token;
            this.key = key;
            this.deadlineNanos = deadlineNanos;
            this.context = context;
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> task = timeout;
            if (task != null) task.cancel(false);
        }
    }
}
