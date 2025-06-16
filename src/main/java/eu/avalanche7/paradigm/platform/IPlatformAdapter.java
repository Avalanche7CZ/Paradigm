package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.utils.MessageParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public interface IPlatformAdapter {

    enum BossBarColor { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }
    enum BossBarOverlay { PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20 }
    enum AdvancementFrame { TASK, CHALLENGE, GOAL }

    void provideMessageParser(MessageParser messageParser);
    MinecraftServer getMinecraftServer();
    void setMinecraftServer(MinecraftServer server);
    List<ServerPlayer> getOnlinePlayers();
    @Nullable ServerPlayer getPlayerByName(String name);
    @Nullable ServerPlayer getPlayerByUuid(UUID uuid);
    String getPlayerName(ServerPlayer player);
    Component getPlayerDisplayName(ServerPlayer player);
    MutableComponent createLiteralComponent(String text);
    MutableComponent createTranslatableComponent(String key, Object... args);
    ItemStack createItemStack(String itemId);
    boolean hasPermission(ServerPlayer player, String permissionNode);
    boolean hasPermission(ServerPlayer player, String permissionNode, int vanillaLevel);
    void sendSystemMessage(ServerPlayer player, Component message);
    void broadcastSystemMessage(Component message);
    void broadcastChatMessage(Component message);
    void broadcastSystemMessage(Component message, String header, String footer, @Nullable ServerPlayer player);
    void sendTitle(ServerPlayer player, Component title, Component subtitle);
    void sendSubtitle(ServerPlayer player, Component subtitle);
    void sendActionBar(ServerPlayer player, Component message);
    void sendBossBar(List<ServerPlayer> players, Component message, int durationSeconds, BossBarColor color, float progress);
    void showPersistentBossBar(ServerPlayer player, Component message, BossBarColor color, BossBarOverlay overlay);
    void removePersistentBossBar(ServerPlayer player);
    void createOrUpdateRestartBossBar(Component message, BossBarColor color, float progress);
    void removeRestartBossBar();
    void displayToast(ServerPlayer player, ResourceLocation id, ItemStack icon, Component title, Component subtitle, AdvancementFrame frame);
    void revokeToast(ServerPlayer player, ResourceLocation id);
    void clearTitles(ServerPlayer player);
    void playSound(ServerPlayer player, String soundId, float volume, float pitch);
    void executeCommandAs(CommandSourceStack source, String command);
    void executeCommandAsConsole(String command);
    String replacePlaceholders(String text, @Nullable ServerPlayer player);
    boolean hasPermissionForCustomCommand(CommandSourceStack source, CustomCommand command);
    void shutdownServer(Component kickMessage);
    void sendSuccess(CommandSourceStack source, Component message, boolean toOps);
    void sendFailure(CommandSourceStack source, Component message);
    void teleportPlayer(ServerPlayer player, double x, double y, double z);
}