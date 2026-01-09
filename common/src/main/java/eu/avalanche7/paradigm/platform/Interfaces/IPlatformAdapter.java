package eu.avalanche7.paradigm.platform.Interfaces;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.utils.MessageParser;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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

    default int getMaxPlayers() {
        return 0;
    }

    default boolean isFirstJoin(IPlayer player) {
        return false;
    }
}
