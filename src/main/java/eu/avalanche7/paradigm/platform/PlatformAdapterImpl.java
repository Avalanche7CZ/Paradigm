package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.*;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
    private final DebugLogger debugLogger;
    private final Map<UUID, ServerBossBar> persistentBossBars = new HashMap<>();
    private ServerBossBar restartBossBar;

    public PlatformAdapterImpl(
            PermissionsHandler permissionsHandler,
            Placeholders placeholders,
            TaskScheduler taskScheduler,
            DebugLogger debugLogger
    ) {
        this.permissionsHandler = permissionsHandler;
        this.placeholders = placeholders;
        this.taskScheduler = taskScheduler;
        this.debugLogger = debugLogger;
    }

    @Override
    public void provideMessageParser(MessageParser messageParser) {
        this.messageParser = messageParser;
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
    public List<ServerPlayerEntity> getOnlinePlayers() {
        return getMinecraftServer().getPlayerManager().getPlayerList();
    }

    @Override
    @Nullable
    public ServerPlayerEntity getPlayerByName(String name) {
        return getMinecraftServer().getPlayerManager().getPlayer(name);
    }

    @Override
    @Nullable
    public ServerPlayerEntity getPlayerByUuid(UUID uuid) {
        return getMinecraftServer().getPlayerManager().getPlayer(uuid);
    }

    @Override
    public String getPlayerName(ServerPlayerEntity player) {
        return player.getName().getString();
    }

    @Override
    public Text getPlayerDisplayName(ServerPlayerEntity player) {
        return player.getDisplayName();
    }

    @Override
    public MutableText createLiteralComponent(String text) {
        return Text.literal(text);
    }

    @Override
    public MutableText createTranslatableComponent(String key, Object... args) {
        return Text.translatable(key, args);
    }

    @Override
    public ItemStack createItemStack(String itemId) {
        Item item = Registries.ITEM.get(Identifier.of(itemId));
        return item != Items.AIR ? new ItemStack(item) : new ItemStack(Items.STONE);
    }

    @Override
    public boolean hasPermission(ServerPlayerEntity player, String permissionNode) {
        return permissionsHandler.hasPermission(player, permissionNode);
    }

    @Override
    public boolean hasPermission(ServerPlayerEntity player, String permissionNode, int vanillaLevel) {
        return this.hasPermission(player, permissionNode) || player.hasPermissionLevel(vanillaLevel);
    }

    @Override
    public void sendSystemMessage(ServerPlayerEntity player, Text message) {
        player.sendMessage(message);
    }

    @Override
    public void broadcastSystemMessage(Text message) {
        if (getMinecraftServer() != null) {
            getMinecraftServer().getPlayerManager().broadcast(message, false);
        }
    }

    @Override
    public void broadcastChatMessage(Text message) {
        if (getMinecraftServer() != null) {
            getMinecraftServer().getPlayerManager().broadcast(message, false);
        }
    }

    @Override
    public void broadcastSystemMessage(Text message, String header, String footer, @Nullable ServerPlayerEntity playerContext) {
        if (messageParser == null) return;
        IPlayer iPlayerContext = playerContext != null ? wrapPlayer(playerContext) : null;
        IComponent headerComp = messageParser.parseMessage(header, iPlayerContext);
        IComponent footerComp = messageParser.parseMessage(footer, iPlayerContext);
        getOnlinePlayers().forEach(p -> {
            p.sendMessage(headerComp.getOriginalText());
            p.sendMessage(message);
            p.sendMessage(footerComp.getOriginalText());
        });
    }

    @Override
    public void sendTitle(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
        if (subtitle != null && !subtitle.getString().isEmpty()) {
            player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        }
    }

    @Override
    public void sendSubtitle(ServerPlayerEntity player, Text subtitle) {
        if (subtitle != null && !subtitle.getString().isEmpty()) {
            player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        }
    }

    @Override
    public void sendActionBar(ServerPlayerEntity player, Text message) {
        player.networkHandler.sendPacket(new OverlayMessageS2CPacket(message));
    }

    private BossBar.Color toMinecraftColor(BossBarColor color) {
        return BossBar.Color.valueOf(color.name());
    }

    private BossBar.Style toMinecraftOverlay(BossBarOverlay overlay) {
        return BossBar.Style.valueOf(overlay.name());
    }

    @Override
    public void sendBossBar(List<ServerPlayerEntity> players, Text message, int durationSeconds, BossBarColor color, float progress) {
        ServerBossBar bossEvent = new ServerBossBar(message, toMinecraftColor(color), BossBar.Style.PROGRESS);
        bossEvent.setPercent(progress);
        players.forEach(bossEvent::addPlayer);
        taskScheduler.schedule(() -> {
            bossEvent.clearPlayers();
            bossEvent.setVisible(false);
        }, durationSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void showPersistentBossBar(ServerPlayerEntity player, Text message, BossBarColor color, BossBarOverlay overlay) {
        removePersistentBossBar(player);
        ServerBossBar bossEvent = new ServerBossBar(message, toMinecraftColor(color), toMinecraftOverlay(overlay));
        bossEvent.addPlayer(player);
        persistentBossBars.put(player.getUuid(), bossEvent);
    }

    @Override
    public void removePersistentBossBar(ServerPlayerEntity player) {
        ServerBossBar bossBar = persistentBossBars.remove(player.getUuid());
        if (bossBar != null) {
            bossBar.clearPlayers();
        }
    }

    @Override
    public void createOrUpdateRestartBossBar(Text message, BossBarColor color, float progress) {
        if (restartBossBar == null) {
            restartBossBar = new ServerBossBar(message, toMinecraftColor(color), BossBar.Style.PROGRESS);
            restartBossBar.setVisible(true);
            getOnlinePlayers().forEach(restartBossBar::addPlayer);
        }
        restartBossBar.setName(message);
        restartBossBar.setPercent(progress);
    }

    @Override
    public void removeRestartBossBar() {
        if (restartBossBar != null) {
            restartBossBar.setVisible(false);
            restartBossBar.clearPlayers();
            restartBossBar = null;
        }
    }

    @Override
    public void clearTitles(ServerPlayerEntity player) {
        player.networkHandler.sendPacket(new ClearTitleS2CPacket(true));
    }

    @Override
    public void playSound(ServerPlayerEntity player, String soundId, net.minecraft.sound.SoundCategory category, float volume, float pitch) {
        SoundEvent soundEvent = Registries.SOUND_EVENT.get(Identifier.of(soundId));
        if (soundEvent != null) {
            player.networkHandler.sendPacket(new PlaySoundS2CPacket(
                    Registries.SOUND_EVENT.getEntry(soundEvent),
                    category,
                    player.getX(), player.getY(), player.getZ(),
                    volume, pitch, player.getWorld().getRandom().nextLong()
            ));
        }
    }

    @Override
    public void executeCommandAs(ServerCommandSource source, String command) {
        getMinecraftServer().getCommandManager().executeWithPrefix(source, command);
    }

    @Override
    public void executeCommandAsConsole(String command) {
        ServerCommandSource consoleSource = getMinecraftServer().getCommandSource().withLevel(4);
        getMinecraftServer().getCommandManager().executeWithPrefix(consoleSource, command);
    }

    @Override
    public String replacePlaceholders(String text, @Nullable ServerPlayerEntity player) {
        return placeholders.replacePlaceholders(text, player);
    }

    @Override
    public boolean hasPermissionForCustomCommand(ServerCommandSource source, CustomCommand command) {
        if (!command.isRequirePermission()) {
            return true;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return true;
        }
        boolean hasPerm = permissionsHandler.hasPermission(player, command.getPermission());
        if (!hasPerm && messageParser != null) {
            IComponent errorComponent = messageParser.parseMessage(command.getPermissionErrorMessage(), wrapPlayer(player));
            player.sendMessage(errorComponent.getOriginalText());
        }
        return hasPerm;
    }

    @Override
    public void shutdownServer(Text kickMessage) {
        if (server != null) {
            try {
                debugLogger.debugLog("PlatformAdapter: Initiating server shutdown sequence.");
                server.getPlayerManager().broadcast(kickMessage, false);
                server.saveAll(true, true, true);
                server.stop(false);
                taskScheduler.scheduleRaw(() -> {
                    debugLogger.debugLog("PlatformAdapter: Forcing JVM exit with status 1 to trigger auto-restart.");
                    System.exit(1);
                }, 500, TimeUnit.MILLISECONDS);

            } catch (Exception e) {
                debugLogger.debugLog("PlatformAdapter: Failed to shutdown server: " + e.getMessage(), e);
            }
        } else {
            debugLogger.debugLog("PlatformAdapter: Shutdown called but server instance is null.");
        }
    }

    @Override
    public void sendSuccess(ServerCommandSource source, Text message, boolean toOps) {
        source.sendFeedback(() -> message, toOps);
    }

    @Override
    public void sendFailure(ServerCommandSource source, Text message) {
        source.sendError(message);
    }

    @Override
    public void teleportPlayer(ServerPlayerEntity player, double x, double y, double z) {
        player.requestTeleport(x, y, z);
    }

    @Override
    public boolean playerHasItem(ServerPlayerEntity player, String itemId, int amount) {
        if (player == null || itemId == null) {
            return false;
        }
        Item item = Registries.ITEM.get(Identifier.of(itemId));
        if (item == Items.AIR) {
            debugLogger.debugLog("PlatformAdapter: Could not find item with ID: " + itemId);
            return false;
        }
        return player.getInventory().count(item) >= amount;
    }

    @Override
    public boolean isPlayerInArea(ServerPlayerEntity player, String worldId, List<Integer> corner1, List<Integer> corner2) {
        if (player == null || worldId == null || corner1 == null || corner2 == null || corner1.size() != 3 || corner2.size() != 3) {
            return false;
        }

        Identifier worldIdentifier = Identifier.of(worldId);
        net.minecraft.registry.RegistryKey<World> targetWorldKey = net.minecraft.registry.RegistryKey.of(RegistryKeys.WORLD, worldIdentifier);

        if (!player.getWorld().getRegistryKey().equals(targetWorldKey)) {
            return false;
        }

        Vec3d pos = player.getPos();
        double pX = pos.getX();
        double pY = pos.getY();
        double pZ = pos.getZ();

        double x1 = Math.min(corner1.get(0), corner2.get(0));
        double y1 = Math.min(corner1.get(1), corner2.get(1));
        double z1 = Math.min(corner1.get(2), corner2.get(2));
        double x2 = Math.max(corner1.get(0), corner2.get(0));
        double y2 = Math.max(corner1.get(1), corner2.get(1));
        double z2 = Math.max(corner1.get(2), corner2.get(2));

        return pX >= x1 && pX <= x2 && pY >= y1 && pY <= y2 && pZ >= z1 && pZ <= z2;
    }

    @Override
    public List<String> getOnlinePlayerNames() {
        return getOnlinePlayers().stream()
                .map(player -> player.getName().getString())
                .toList();
    }

    @Override
    public List<String> getWorldNames() {
        if (server == null) return List.of();
        List<String> worldNames = new ArrayList<>();
        for (net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
            worldNames.add(world.getRegistryKey().getValue().toString());
        }
        return worldNames;
    }

    @Override
    public IPlayer wrapPlayer(ServerPlayerEntity player) {
        return MinecraftPlayer.of(player);
    }
}