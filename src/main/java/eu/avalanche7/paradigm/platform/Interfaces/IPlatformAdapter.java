package eu.avalanche7.paradigm.platform.Interfaces;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.utils.MessageParser;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public interface IPlatformAdapter {

    enum BossBarColor { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }
    enum BossBarOverlay { PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20 }
    enum AdvancementFrame { TASK, CHALLENGE, GOAL }
    enum SoundCategory { MASTER, MUSIC, RECORDS, WEATHER, BLOCKS, HOSTILE, NEUTRAL, PLAYERS, AMBIENT, VOICE }

    void provideMessageParser(MessageParser messageParser);
    Object getMinecraftServer();
    void setMinecraftServer(Object server);
    List<IPlayer> getOnlinePlayers();
    @Nullable
    IPlayer getPlayerByName(String name);
    @Nullable
    IPlayer getPlayerByUuid(UUID uuid);
    String getPlayerName(IPlayer player);
    IComponent getPlayerDisplayName(IPlayer player);
    IComponent createLiteralComponent(String text);
    IComponent createTranslatableComponent(String key, Object... args);
    Object createItemStack(String itemId);
    boolean hasPermission(IPlayer player, String permissionNode);
    boolean hasPermission(IPlayer player, String permissionNode, int vanillaLevel);
    boolean hasPermissionNode(IPlayer player, String permission);
    boolean hasCommandPermission(Object source, String permission);
    boolean hasCommandPermission(Object source, String permission, int vanillaLevel);
    void sendSystemMessage(IPlayer player, IComponent message);
    void sendSystemMessage(IPlayer player, String message);
    void broadcastSystemMessage(IComponent message);
    void broadcastChatMessage(IComponent message);
    void broadcastSystemMessage(IComponent message, String header, String footer, @Nullable IPlayer playerContext);
    void sendTitle(IPlayer player, IComponent title, IComponent subtitle);
    void sendTitle(IPlayer player, String title, String subtitle);
    void sendSubtitle(IPlayer player, IComponent subtitle);
    void sendActionBar(IPlayer player, IComponent message);
    void sendActionBar(IPlayer player, String message);
    void clearTitles(IPlayer player);
    void sendBossBar(List<IPlayer> players, IComponent message, int durationSeconds, BossBarColor color, float progress);
    void sendBossBar(List<IPlayer> players, String message, int durationSeconds, BossBarColor color, float progress);
    void showPersistentBossBar(IPlayer player, IComponent message, BossBarColor color, BossBarOverlay overlay);
    void removePersistentBossBar(IPlayer player);
    void createOrUpdateRestartBossBar(IComponent message, BossBarColor color, float progress);
    void createOrUpdateRestartBossBar(String message, BossBarColor color, float progress);
    void removeRestartBossBar();
    void playSound(IPlayer player, String soundId, SoundCategory category, float volume, float pitch);
    String replacePlaceholders(String text, @Nullable IPlayer player);
    boolean hasPermissionForCustomCommand(Object source, CustomCommand command);
    void shutdownServer(IComponent kickMessage);
    void shutdownServer(String kickMessage);
    void sendSuccess(Object source, IComponent message, boolean toOps);
    void sendFailure(Object source, IComponent message);
    void teleportPlayer(IPlayer player, double x, double y, double z);
    boolean playerHasItem(IPlayer player, String itemId, int amount);
    boolean isPlayerInArea(IPlayer player, String worldId, List<Integer> corner1, List<Integer> corner2);
    void executeCommandAs(Object source, String command);
    void executeCommandAsConsole(String command);
    boolean isModLoaded(String modId);
    ICommandSource wrapCommandSource(Object source);
    void sendSuccess(ICommandSource source, IComponent message, boolean toOps);
    void sendFailure(ICommandSource source, IComponent message);
    boolean hasCommandPermission(ICommandSource source, String permission);
    boolean hasCommandPermission(ICommandSource source, String permission, int vanillaLevel);
    void executeCommandAs(ICommandSource source, String command);
    IEventSystem getEventSystem();
    List<String> getWorldNames();
    List<String> getOnlinePlayerNames();
}