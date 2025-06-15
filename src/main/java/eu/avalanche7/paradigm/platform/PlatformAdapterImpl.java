package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PlatformAdapterImpl implements IPlatformAdapter {

    private MinecraftServer server;
    private MessageParser messageParser;
    private final PermissionsHandler permissionsHandler;
    private final Placeholders placeholders;
    private final TaskScheduler taskScheduler;
    private final Map<UUID, ServerBossEvent> persistentBossBars = new HashMap<>();
    private ServerBossEvent restartBossBar;

    public PlatformAdapterImpl(
            PermissionsHandler permissionsHandler,
            Placeholders placeholders,
            TaskScheduler taskScheduler
    ) {
        this.permissionsHandler = permissionsHandler;
        this.placeholders = placeholders;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void provideMessageParser(MessageParser messageParser) {
        this.messageParser = messageParser;
    }

    private BossEvent.BossBarColor toMinecraftColor(BossBarColor color) {
        return BossEvent.BossBarColor.valueOf(color.name());
    }

    private BossEvent.BossBarOverlay toMinecraftOverlay(BossBarOverlay overlay) {
        return BossEvent.BossBarOverlay.valueOf(overlay.name());
    }

    @Override
    public MinecraftServer getMinecraftServer() {
        return this.server;
    }

    @Override
    public void setMinecraftServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public List<ServerPlayer> getOnlinePlayers() {
        return getMinecraftServer().getPlayerList().getPlayers();
    }

    @Override
    @Nullable
    public ServerPlayer getPlayerByName(String name) {
        return getMinecraftServer().getPlayerList().getPlayerByName(name);
    }

    @Override
    @Nullable
    public ServerPlayer getPlayerByUuid(UUID uuid) {
        return getMinecraftServer().getPlayerList().getPlayer(uuid);
    }

    @Override
    public String getPlayerName(ServerPlayer player) {
        return player.getName().getString();
    }

    @Override
    public Component getPlayerDisplayName(ServerPlayer player) {
        return player.getDisplayName();
    }

    @Override
    public MutableComponent createLiteralComponent(String text) {
        return new TextComponent(text);
    }

    @Override
    public MutableComponent createTranslatableComponent(String key, Object... args) {
        return new TranslatableComponent(key, args);
    }

    @Override
    public boolean hasPermission(ServerPlayer player, String permissionNode) {
        return permissionsHandler.hasPermission(player, permissionNode);
    }

    @Override
    public boolean hasPermission(ServerPlayer player, String permissionNode, int vanillaLevel) {
        return this.hasPermission(player, permissionNode) || player.hasPermissions(vanillaLevel);
    }

    @Override
    public void sendSystemMessage(ServerPlayer player, Component message) {
        player.sendMessage(message, Util.NIL_UUID);
    }

    @Override
    public void broadcastSystemMessage(Component message) {
        if (getMinecraftServer() != null) {
            getMinecraftServer().getPlayerList().broadcastMessage(message, ChatType.SYSTEM, Util.NIL_UUID);
        }
    }

    @Override
    public void broadcastChatMessage(Component message) {
        if (getMinecraftServer() != null) {
            getMinecraftServer().getPlayerList().broadcastMessage(message, ChatType.CHAT, Util.NIL_UUID);
        }
    }

    @Override
    public void broadcastSystemMessage(Component message, String header, String footer, @Nullable ServerPlayer playerContext) {
        if (messageParser == null) return;
        Component headerComp = messageParser.parseMessage(header, playerContext);
        Component footerComp = messageParser.parseMessage(footer, playerContext);
        getOnlinePlayers().forEach(p -> {
            this.sendSystemMessage(p, headerComp);
            this.sendSystemMessage(p, message);
            this.sendSystemMessage(p, footerComp);
        });
    }

    @Override
    public void sendTitle(ServerPlayer player, Component title, Component subtitle) {
        player.connection.send(new ClientboundSetTitleTextPacket(title));
        if (subtitle != null && !subtitle.getString().isEmpty()) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
    }

    @Override
    public void sendSubtitle(ServerPlayer player, Component subtitle) {
        if (subtitle != null && !subtitle.getString().isEmpty()) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
    }

    @Override
    public void sendActionBar(ServerPlayer player, Component message) {
        player.connection.send(new ClientboundSetActionBarTextPacket(message));
    }

    @Override
    public void sendBossBar(List<ServerPlayer> players, Component message, int durationSeconds, BossBarColor color, float progress) {
        ServerBossEvent bossEvent = new ServerBossEvent(message, toMinecraftColor(color), BossEvent.BossBarOverlay.PROGRESS);
        bossEvent.setProgress(progress);
        players.forEach(bossEvent::addPlayer);
        taskScheduler.schedule(() -> {
            bossEvent.removeAllPlayers();
            bossEvent.setVisible(false);
        }, durationSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void showPersistentBossBar(ServerPlayer player, Component message, BossBarColor color, BossBarOverlay overlay) {
        removePersistentBossBar(player);
        ServerBossEvent bossEvent = new ServerBossEvent(message, toMinecraftColor(color), toMinecraftOverlay(overlay));
        bossEvent.addPlayer(player);
        persistentBossBars.put(player.getUUID(), bossEvent);
    }

    @Override
    public void removePersistentBossBar(ServerPlayer player) {
        ServerBossEvent bossBar = persistentBossBars.remove(player.getUUID());
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    @Override
    public void createOrUpdateRestartBossBar(Component message, BossBarColor color, float progress) {
        if (restartBossBar == null) {
            restartBossBar = new ServerBossEvent(message, toMinecraftColor(color), BossEvent.BossBarOverlay.PROGRESS);
            restartBossBar.setVisible(true);
            getOnlinePlayers().forEach(restartBossBar::addPlayer);
        }
        restartBossBar.setName(message);
        restartBossBar.setProgress(progress);
    }

    @Override
    public void removeRestartBossBar() {
        if (restartBossBar != null) {
            restartBossBar.setVisible(false);
            restartBossBar.removeAllPlayers();
            restartBossBar = null;
        }
    }

    @Override
    public void clearTitles(ServerPlayer player) {
        player.connection.send(new ClientboundClearTitlesPacket(true));
    }

    @Override
    public void playSound(ServerPlayer player, String soundId, float volume, float pitch) {
        SoundEvent soundEvent = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(soundId));
        if (soundEvent != null) {
            player.playNotifySound(soundEvent, SoundSource.MASTER, volume, pitch);
        }
    }

    @Override
    public void executeCommandAs(CommandSourceStack source, String command) {
        getMinecraftServer().getCommands().performCommand(source, command);
    }

    @Override
    public void executeCommandAsConsole(String command) {
        CommandSourceStack consoleSource = getMinecraftServer().createCommandSourceStack().withPermission(4);
        getMinecraftServer().getCommands().performCommand(consoleSource, command);
    }

    @Override
    public String replacePlaceholders(String text, @Nullable ServerPlayer player) {
        return placeholders.replacePlaceholders(text, player);
    }

    @Override
    public boolean hasPermissionForCustomCommand(CommandSourceStack source, CustomCommand command) {
        if (!command.isRequirePermission()) {
            return true;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return true;
        }
        boolean hasPerm = permissionsHandler.hasPermission(player, command.getPermission());
        if (!hasPerm && messageParser != null) {
            String errorMessage = command.getPermissionErrorMessage();
            this.sendSystemMessage(player, messageParser.parseMessage(errorMessage, player));
        }
        return hasPerm;
    }

    @Override
    public void shutdownServer(Component kickMessage) {
        if (server != null) {
            try {
                server.getPlayerList().broadcastMessage(kickMessage, ChatType.SYSTEM, Util.NIL_UUID);
                server.saveEverything(true, true, true);
                server.halt(false);
            } catch (Exception e) {}
        }
    }

    @Override
    public void sendSuccess(CommandSourceStack source, Component message, boolean toOps) {
        source.sendSuccess(message, toOps);
    }

    @Override
    public void sendFailure(CommandSourceStack source, Component message) {
        source.sendFailure(message);
    }

    @Override
    public void teleportPlayer(ServerPlayer player, double x, double y, double z) {
        player.teleportTo(x, y, z);
    }
}
