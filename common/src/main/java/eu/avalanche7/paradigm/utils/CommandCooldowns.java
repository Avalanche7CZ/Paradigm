package eu.avalanche7.paradigm.utils;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import eu.avalanche7.paradigm.configs.CooldownConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class CommandCooldowns {
    private static final Map<WarmupKey, Warmup> PENDING_WARMUPS = new ConcurrentHashMap<>();

    private record WarmupKey(Services services, UUID uuid, String command) {}
    private record Warmup(PlayerConnection connection, BooleanSupplier activation, long until) {}

    public static void clearWarmups(Services services) {
        PENDING_WARMUPS.keySet().removeIf(key -> key.services() == services);
    }

    private CommandCooldowns() {
    }

    @FunctionalInterface
    public interface CommandAction {
        int run();
    }

    @FunctionalInterface
    public interface PlayerCommandAction {
        int run(IPlayer currentPlayer);
    }

    public static int run(Services services, IPlayer player, String commandName, CommandAction action) {
        return runInternal(services, player, commandName, true, current -> action.run());
    }

    public static int run(Services services, IPlayer player, String commandName, PlayerCommandAction action) {
        return runInternal(services, player, commandName, true, action);
    }

    public static int run(Services services, IPlayer player, String commandName, BooleanSupplier activation, PlayerCommandAction action) {
        return runInternal(services, player, commandName, true, action, true, activation);
    }

    public static int runCooldownOnly(Services services, IPlayer player, String commandName, CommandAction action) {
        return runInternal(services, player, commandName, false, current -> action.run());
    }

    public static int runCooldownOnly(Services services, IPlayer player, String commandName, PlayerCommandAction action) {
        return runInternal(services, player, commandName, false, action);
    }

    public static int runAsync(Services services, IPlayer player, String commandName,
            java.util.function.Function<IPlayer, java.util.concurrent.CompletableFuture<Integer>> action) {
        return runInternal(services, player, commandName, true, current -> {
            var result = action.apply(current);
            result.thenAccept(value -> {
                UUID uuid = parseUuid(current.getUUID());
                String key = normalize(commandName);
                if (value > 0 && uuid != null && key != null) {
                    CooldownConfigHandler.setLastUsage(uuid, key, System.currentTimeMillis());
                }
            });
            return result.getNow(1);
        }, false, () -> true);
    }

    private static int runInternal(
            Services services,
            IPlayer player,
            String commandName,
            boolean allowWarmup,
            PlayerCommandAction action
    ) {
        return runInternal(services, player, commandName, allowWarmup, action, true, () -> true);
    }

    private static int runInternal(Services services, IPlayer player, String commandName,
            boolean allowWarmup, PlayerCommandAction action, boolean recordUsage, BooleanSupplier activation) {
        if (action == null || (services != null && services.isStopping()) || !activation.getAsBoolean()) {
            return 0;
        }
        if (services == null || player == null) {
            return action.run(player);
        }

        UUID uuid = parseUuid(player.getUUID());
        String key = normalize(commandName);
        if (uuid == null || key == null) {
            return action.run(player);
        }

        long now = System.currentTimeMillis();
        int cooldownSeconds = CooldownConfigHandler.getCommandCooldownSeconds(key);
        if (cooldownSeconds > 0) {
            long lastUsage = CooldownConfigHandler.getLastUsage(uuid, key);
            long remainingMs = lastUsage + cooldownSeconds * 1000L - now;
            if (remainingMs > 0L) {
                send(services, player, "Please wait " + secondsCeil(remainingMs) + "s before using /" + key + " again.");
                return 0;
            }
        }

        int warmupSeconds = allowWarmup ? CooldownConfigHandler.getCommandWarmupSeconds(key) : 0;
        if (warmupSeconds <= 0) {
            int result = action.run(player);
            if (recordUsage && result > 0) {
                CooldownConfigHandler.setLastUsage(uuid, key, System.currentTimeMillis());
            }
            return result;
        }

        var pendingKey = new WarmupKey(services, uuid, key);
        var previous = PENDING_WARMUPS.get(pendingKey);
        if (previous != null && previous.until() > now && previous.connection().matches(player) && previous.activation().getAsBoolean()) {
            send(services, player, "Teleport already warming up. Please wait " + secondsCeil(previous.until() - now) + "s.");
            return 0;
        }

        var warmup = new Warmup(new PlayerConnection(player), activation, now + warmupSeconds * 1000L);
        PENDING_WARMUPS.put(pendingKey, warmup);
        try {
            var task = services.getTaskScheduler().schedule(() -> {
                try {
                    if (PENDING_WARMUPS.get(pendingKey) != warmup || services.isStopping() || !activation.getAsBoolean()) return;
                    IPlayer online = services.getPlatformAdapter().getPlayerByUuid(uuid.toString());
                    if (!warmup.connection().matches(online)) return;
                    int result = action.run(online);
                    if (recordUsage && result > 0) {
                        CooldownConfigHandler.setLastUsage(uuid, key, System.currentTimeMillis());
                    }
                } finally {
                    PENDING_WARMUPS.remove(pendingKey, warmup);
                }
            }, warmupSeconds, TimeUnit.SECONDS);
            if (task == null || task.isCancelled()) {
                PENDING_WARMUPS.remove(pendingKey, warmup);
                return 0;
            }
        } catch (RuntimeException | Error failure) {
            PENDING_WARMUPS.remove(pendingKey, warmup);
            throw failure;
        }
        send(services, player, "Teleporting in " + warmupSeconds + "s...");
        return 1;
    }

    private static void send(Services services, IPlayer player, String message) {
        String decorated = "<color:#F59E0B><bold>[Cooldown]</bold></color> <color:#E5E7EB>" + escape(message) + "</color>";
        services.getPlatformAdapter().sendSystemMessage(player, services.getMessageParser().parseMessage(decorated, player));
    }

    private static long secondsCeil(long millis) {
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("<", "").replace(">", "");
    }
}
