package eu.avalanche7.paradigm.platform.Interfaces;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.utils.MessageParser;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public interface IPlatformAdapter {
    enum BossBarColor { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }
    enum BossBarOverlay { PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20 }

    void provideMessageParser(MessageParser messageParser);
    MinecraftServer getMinecraftServer();
    void setMinecraftServer(MinecraftServer server);
    List<ServerPlayerEntity> getOnlinePlayers();
    @Nullable ServerPlayerEntity getPlayerByName(String name);
    @Nullable ServerPlayerEntity getPlayerByUuid(UUID uuid);
    String getPlayerName(ServerPlayerEntity player);
    Text getPlayerDisplayName(ServerPlayerEntity player);
    MutableText createLiteralComponent(String text);
    MutableText createTranslatableComponent(String key, Object... args);
    ItemStack createItemStack(String itemId);
    boolean hasPermission(ServerPlayerEntity player, String permissionNode);
    boolean hasPermission(ServerPlayerEntity player, String permissionNode, int vanillaLevel);
    void sendSystemMessage(ServerPlayerEntity player, Text message);
    void broadcastSystemMessage(Text message);
    void broadcastChatMessage(Text message);
    void broadcastSystemMessage(Text message, String header, String footer, @Nullable ServerPlayerEntity player);
    void sendTitle(ServerPlayerEntity player, Text title, Text subtitle);
    void sendSubtitle(ServerPlayerEntity player, Text subtitle);
    void sendActionBar(ServerPlayerEntity player, Text message);
    void sendBossBar(List<ServerPlayerEntity> players, Text message, int durationSeconds, BossBarColor color, float progress);
    void showPersistentBossBar(ServerPlayerEntity player, Text message, BossBarColor color, BossBarOverlay overlay);
    void removePersistentBossBar(ServerPlayerEntity player);
    void createOrUpdateRestartBossBar(Text message, BossBarColor color, float progress);
    void removeRestartBossBar();
    void clearTitles(ServerPlayerEntity player);
    void playSound(ServerPlayerEntity player, String soundId, net.minecraft.sound.SoundCategory category, float volume, float pitch);
    void executeCommandAs(ServerCommandSource source, String command);
    void executeCommandAsConsole(String command);
    String replacePlaceholders(String text, @Nullable ServerPlayerEntity player);
    boolean hasPermissionForCustomCommand(ServerCommandSource source, CustomCommand command);
    void shutdownServer(Text kickMessage);
    void sendSuccess(ServerCommandSource source, Text message, boolean toOps);
    void sendFailure(ServerCommandSource source, Text message);
    void teleportPlayer(ServerPlayerEntity player, double x, double y, double z);
    boolean playerHasItem(ServerPlayerEntity player, String itemId, int amount);
    boolean isPlayerInArea(ServerPlayerEntity player, String worldId, List<Integer> corner1, List<Integer> corner2);
    List<String> getOnlinePlayerNames();
    List<String> getWorldNames();
    IPlayer wrapPlayer(ServerPlayerEntity player);
    ICommandSource wrapCommandSource(ServerCommandSource source);

    IComponent createEmptyComponent();
    IComponent parseFormattingCode(String code, IComponent currentComponent);
    IComponent parseHexColor(String hex, IComponent currentComponent);

    IComponent wrap(Text text);
    IComponent createComponentFromLiteral(String text);
    String getMinecraftVersion();

    // Style creation methods for cross-version compatibility
    net.minecraft.text.Style createStyleWithClickEvent(net.minecraft.text.Style baseStyle, String action, String value);
    net.minecraft.text.Style createStyleWithHoverEvent(net.minecraft.text.Style baseStyle, Text hoverText);
}
