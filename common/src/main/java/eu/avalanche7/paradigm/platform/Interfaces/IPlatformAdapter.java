package eu.avalanche7.paradigm.platform.Interfaces;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.utils.MessageParser;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface IPlatformAdapter {
    enum BossBarColor { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }
    enum BossBarOverlay { PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20 }

    void provideMessageParser(MessageParser messageParser);
    Object getMinecraftServer();
    void setMinecraftServer(Object server);
    List<IPlayer> getOnlinePlayers();
    @Nullable IPlayer getPlayerByName(String name);
    @Nullable IPlayer getPlayerByUuid(String uuid);
    String getPlayerName(IPlayer player);
    IComponent getPlayerDisplayName(IPlayer player);
    IComponent createLiteralComponent(String text);
    IComponent createTranslatableComponent(String key, Object... args);
    Object createItemStack(String itemId);
    boolean hasPermission(IPlayer player, String permissionNode);
    boolean hasPermission(IPlayer player, String permissionNode, int vanillaLevel);
    void sendSystemMessage(IPlayer player, IComponent message);
    void broadcastSystemMessage(IComponent message);
    void broadcastChatMessage(IComponent message);
    void broadcastSystemMessage(IComponent message, String header, String footer, @Nullable IPlayer player);
    void sendTitle(IPlayer player, IComponent title, IComponent subtitle);
    void sendSubtitle(IPlayer player, IComponent subtitle);
    void sendActionBar(IPlayer player, IComponent message);
    void sendBossBar(List<IPlayer> players, IComponent message, int durationSeconds, BossBarColor color, float progress);
    void showPersistentBossBar(IPlayer player, IComponent message, BossBarColor color, BossBarOverlay overlay);
    void removePersistentBossBar(IPlayer player);
    void createOrUpdateRestartBossBar(IComponent message, BossBarColor color, float progress);
    void removeRestartBossBar();
    void clearTitles(IPlayer player);
    void playSound(IPlayer player, String soundId, String category, float volume, float pitch);

    void executeCommandAs(ICommandSource source, String command);
    void executeCommandAsConsole(String command);

    String replacePlaceholders(String text, @Nullable IPlayer player);
    boolean hasPermissionForCustomCommand(ICommandSource source, CustomCommand command);

    void shutdownServer(IComponent kickMessage);
    void sendSuccess(ICommandSource source, IComponent message, boolean toOps);
    void sendFailure(ICommandSource source, IComponent message);

    void teleportPlayer(IPlayer player, double x, double y, double z);
    boolean playerHasItem(IPlayer player, String itemId, int amount);
    boolean isPlayerInArea(IPlayer player, String worldId, List<Integer> corner1, List<Integer> corner2);

    List<String> getOnlinePlayerNames();
    List<String> getWorldNames();

    IPlayer wrapPlayer(Object player);
    ICommandSource wrapCommandSource(Object source);

    IComponent createEmptyComponent();
    IComponent parseFormattingCode(String code, IComponent currentComponent);
    IComponent parseHexColor(String hex, IComponent currentComponent);

    IComponent wrap(Object text);
    IComponent createComponentFromLiteral(String text);
    String getMinecraftVersion();

    Object createStyleWithClickEvent(Object baseStyle, String action, String value);
    Object createStyleWithHoverEvent(Object baseStyle, Object hoverText);

    IConfig getConfig();
    IEventSystem getEventSystem();

    ICommandBuilder createCommandBuilder();
    void registerCommand(ICommandBuilder builder);

    default Optional<PlayerDataStore.StoredLocation> getPlayerLocation(IPlayer player) {
        if (player == null || player.getOriginalPlayer() == null) {
            return Optional.empty();
        }

        // Prefer platform-provided values when available; reflection is only fallback.
        String worldId = player.getWorldId();
        Double x = player.getX();
        Double y = player.getY();
        Double z = player.getZ();
        Float yaw = player.getYaw();
        Float pitch = player.getPitch();

        Object handle = player.getOriginalPlayer();
        if (worldId == null) worldId = extractWorldId(handle);
        if (x == null) x = invokeDouble(handle, "getX");
        if (y == null) y = invokeDouble(handle, "getY");
        if (z == null) z = invokeDouble(handle, "getZ");
        if (yaw == null) yaw = firstFloat(handle, "getYRot", "getYaw");
        if (pitch == null) pitch = firstFloat(handle, "getXRot", "getPitch");

        if (worldId == null || x == null || y == null || z == null) {
            return Optional.empty();
        }

        return Optional.of(new PlayerDataStore.StoredLocation(worldId, x, y, z, yaw != null ? yaw : 0.0f, pitch != null ? pitch : 0.0f));
    }

    default boolean teleportPlayer(IPlayer player, PlayerDataStore.StoredLocation location) {
        if (player == null || location == null || location.getWorldId() == null || location.getWorldId().isBlank()) {
            return false;
        }

        Optional<PlayerDataStore.StoredLocation> current = getPlayerLocation(player);
        if (current.isPresent() && sameWorld(current.get().getWorldId(), location.getWorldId())) {
            teleportPlayer(player, location.getX(), location.getY(), location.getZ());
            applyRotation(player, location.getYaw(), location.getPitch());
            return true;
        }

        String command = String.format(
                Locale.US,
                "execute in %s run tp %s %.3f %.3f %.3f %.2f %.2f",
                location.getWorldId(),
                player.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
        executeCommandAsConsole(command);
        return true;
    }

    private static boolean sameWorld(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static void applyRotation(IPlayer player, float yaw, float pitch) {
        if (player == null || player.getOriginalPlayer() == null) {
            return;
        }
        Object handle = player.getOriginalPlayer();
        invokeWithArgs(handle, "setYaw", new Class<?>[]{float.class}, yaw);
        invokeWithArgs(handle, "setPitch", new Class<?>[]{float.class}, pitch);
    }

    private static String extractWorldId(Object handle) {
        Object level = firstObject(handle,
                "serverLevel",
                "getServerWorld",
                "getCommandSenderWorld",
                "level",
                "level()",
                "getLevel",
                "getLevel()",
                "level",
                "getWorld",
                "getWorld()",
                "world",
                "getEntityWorld"
        );
        if (level == null) {
            return null;
        }

        Object key = firstObject(level, "dimension", "getRegistryKey", "dimensionKey");
        if (key == null) {
            key = firstObject(level, "dimension", "dimension()", "getDimension", "registryKey");
        }
        if (key != null) {
            Object id = firstObject(key, "location", "getValue", "value");
            if (id == null) {
                id = firstObject(key, "location", "location()", "getPath");
            }
            if (id != null) {
                return sanitizeWorldId(id.toString());
            }
            String parsed = sanitizeWorldId(key.toString());
            if (parsed != null) {
                return parsed;
            }
        }
        return sanitizeWorldId(level.toString());
    }

    private static Object firstObject(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            String cleaned = name.endsWith("()") ? name.substring(0, name.length() - 2) : name;
            Object value = invokeNoArg(target, cleaned);
            if (value != null) {
                return value;
            }
            value = readField(target, cleaned);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Double invokeDouble(Object target, String name) {
        Object value = invokeNoArg(target, name);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static Float firstFloat(Object target, String... names) {
        for (String name : names) {
            Object value = invokeNoArg(target, name);
            if (value instanceof Number n) {
                return n.floatValue();
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeWithArgs(Object target, String name, Class<?>[] signature, Object... args) {
        try {
            Method method = target.getClass().getMethod(name, signature);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (Throwable ignored) {
        }
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sanitizeWorldId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("([a-z0-9_.-]+:[a-z0-9_./-]+)").matcher(raw.toLowerCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1) : null;
    }

    default int getMaxPlayers() {
        return 0;
    }

    default boolean isFirstJoin(IPlayer player) {
        return false;
    }
}
