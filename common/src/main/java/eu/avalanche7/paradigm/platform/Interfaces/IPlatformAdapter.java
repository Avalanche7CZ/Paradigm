package eu.avalanche7.paradigm.platform.Interfaces;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.utils.CommandPriority;
import eu.avalanche7.paradigm.utils.MessageParser;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public interface IPlatformAdapter {
    enum BossBarColor { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }
    enum BossBarOverlay { PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20 }
    record InventoryItem(int slot, int count, String displayName) { }

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

    default void executeOnServerThread(Runnable task) {
        if (task != null) task.run();
    }

    default boolean unregisterCommandRoot(String rootLiteral) {
        Object dispatcher = getCommandDispatcher();
        return dispatcher != null && CommandPriority.unregisterRootLiteral(dispatcher, rootLiteral);
    }

    default Object getCommandDispatcher() {
        return null;
    }

    default ICommandSource createCommandSourceForPlayer(IPlayer player) {
        return null;
    }

    boolean setGameMode(IPlayer player, String mode);
    boolean setMovementSpeed(IPlayer player, double baseValue);
    boolean setTimeOfDay(long timeOfDay);
    boolean setWeather(String weather);
    boolean healPlayer(IPlayer player);
    boolean feedPlayer(IPlayer player);
    Boolean toggleFlight(IPlayer player);
    boolean setPlayerSpeed(IPlayer player, float walkSpeed, float flySpeed, double movementBaseValue);
    boolean clearPlayerInventory(IPlayer player);
    boolean setPlayerInvulnerable(IPlayer player, boolean enabled);
    boolean setPlayerVanished(IPlayer player, boolean enabled);
    List<InventoryItem> inspectPlayerInventory(IPlayer player, boolean enderChest);
    int repairPlayerItems(IPlayer player, boolean all);
    boolean enchantMainHand(IPlayer player, String enchantmentId, int level);
    Integer getHighestBlockY(IPlayer player);
    boolean jumpPlayerForward(IPlayer player, int distance);

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
    String getLoaderName();

    default String getPlayerRemoteAddress(IPlayer player) {
        return null;
    }

    boolean disconnectPlayer(IPlayer player, IComponent reason);

    Object createStyleWithClickEvent(Object baseStyle, String action, String value);
    Object createStyleWithHoverEvent(Object baseStyle, Object hoverText);

    IConfig getConfig();
    IEventSystem getEventSystem();

    ICommandBuilder createCommandBuilder();
    void registerCommand(ICommandBuilder builder);

    default void refreshPlayerCommandTree(@Nullable IPlayer player) {
    }

    default void refreshAllPlayerCommandTrees() {
        List<IPlayer> players;
        try {
            players = getOnlinePlayers();
        } catch (Throwable ignored) {
            return;
        }
        if (players == null || players.isEmpty()) {
            return;
        }
        for (IPlayer player : players) {
            try {
                refreshPlayerCommandTree(player);
            } catch (Throwable ignored) {
            }
        }
    }

    default Optional<PlayerDataStore.StoredLocation> getPlayerLocation(IPlayer player) {
        if (player == null || player.getOriginalPlayer() == null) {
            return Optional.empty();
        }
        String worldId = player.getWorldId();
        Double x = player.getX();
        Double y = player.getY();
        Double z = player.getZ();
        Float yaw = player.getYaw();
        Float pitch = player.getPitch();

        if (worldId == null || x == null || y == null || z == null) {
            return Optional.empty();
        }

        return Optional.of(new PlayerDataStore.StoredLocation(worldId, x, y, z, yaw != null ? yaw : 0.0f, pitch != null ? pitch : 0.0f));
    }

    default boolean teleportPlayer(IPlayer player, PlayerDataStore.StoredLocation location) {
        return false;
    }

    default int getMaxPlayers() {
        return 0;
    }

    default boolean isFirstJoin(IPlayer player) {
        return false;
    }
}
