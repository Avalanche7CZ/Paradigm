package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.CooldownConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class CommandCooldowns {
    private static final Map<String, Long> PENDING_WARMUPS = new ConcurrentHashMap<>();

    private CommandCooldowns() {
    }

    @FunctionalInterface
    public interface CommandAction {
        int run();
    }

    public static int run(Services services, IPlayer player, String commandName, CommandAction action) {
        return runInternal(services, player, commandName, true, action);
    }

    public static int runCooldownOnly(Services services, IPlayer player, String commandName, CommandAction action) {
        return runInternal(services, player, commandName, false, action);
    }

    private static int runInternal(Services services, IPlayer player, String commandName, boolean allowWarmup, CommandAction action) {
        if (services == null || player == null || action == null) {
            return action != null ? action.run() : 0;
        }

        UUID uuid = parseUuid(player.getUUID());
        String key = normalize(commandName);
        if (uuid == null || key == null) {
            return action.run();
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
            int result = action.run();
            if (result > 0) {
                CooldownConfigHandler.setLastUsage(uuid, key, System.currentTimeMillis());
            }
            return result;
        }

        String pendingKey = uuid + ":" + key;
        Long pendingUntil = PENDING_WARMUPS.get(pendingKey);
        if (pendingUntil != null && pendingUntil > now) {
            send(services, player, "Teleport already warming up. Please wait " + secondsCeil(pendingUntil - now) + "s.");
            return 0;
        }

        long until = now + warmupSeconds * 1000L;
        PENDING_WARMUPS.put(pendingKey, until);
        send(services, player, "Teleporting in " + warmupSeconds + "s...");

        services.getTaskScheduler().schedule(() -> {
            try {
                IPlayer online = services.getPlatformAdapter().getPlayerByUuid(uuid.toString());
                if (online == null) {
                    return;
                }
                int result = action.run();
                if (result > 0) {
                    CooldownConfigHandler.setLastUsage(uuid, key, System.currentTimeMillis());
                }
            } finally {
                PENDING_WARMUPS.remove(pendingKey);
            }
        }, warmupSeconds, TimeUnit.SECONDS);
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
