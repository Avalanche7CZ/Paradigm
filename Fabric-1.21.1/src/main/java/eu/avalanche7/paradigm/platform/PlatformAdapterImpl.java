package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
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
import net.minecraft.text.*;
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
    private final Map<String, ServerBossBar> persistentBossBars = new HashMap<>();
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
    public List<IPlayer> getOnlinePlayers() {
        if (server == null || server.getPlayerManager() == null) return new ArrayList<>();
        return server.getPlayerManager().getPlayerList().stream()
                .map(this::wrapPlayer)
                .toList();
    }

    @Override
    @Nullable
    public IPlayer getPlayerByName(String name) {
        if (server == null || server.getPlayerManager() == null) return null;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
        return player != null ? wrapPlayer(player) : null;
    }

    @Override
    @Nullable
    public IPlayer getPlayerByUuid(String uuid) {
        if (server == null || server.getPlayerManager() == null) return null;
        try {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(java.util.UUID.fromString(uuid));
            return player != null ? wrapPlayer(player) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String getPlayerName(IPlayer player) {
        return player.getName();
    }

    @Override
    public IComponent getPlayerDisplayName(IPlayer player) {
        if (player instanceof MinecraftPlayer mp) {
            return wrap(mp.getHandle().getDisplayName());
        }
        return createLiteralComponent(player.getName());
    }

    @Override
    public IComponent createLiteralComponent(String text) {
        return new MinecraftComponent(Text.literal(text));
    }

    @Override
    public IComponent createTranslatableComponent(String key, Object... args) {
        return new MinecraftComponent(Text.translatable(key, args));
    }

    @Override
    public ItemStack createItemStack(String itemId) {
        Item item = Registries.ITEM.get(Identifier.of(itemId));
        return item != Items.AIR ? new ItemStack(item) : new ItemStack(Items.STONE);
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode) {
        if (player instanceof MinecraftPlayer mp) {
            return permissionsHandler.hasPermission(mp.getHandle(), permissionNode);
        }
        return false;
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode, int vanillaLevel) {
        if (player instanceof MinecraftPlayer mp) {
            return this.hasPermission(player, permissionNode) || mp.getHandle().hasPermissionLevel(vanillaLevel);
        }
        return false;
    }

    @Override
    public void sendSystemMessage(IPlayer player, IComponent message) {
        if (player instanceof MinecraftPlayer mp && message instanceof MinecraftComponent mc) {
            mp.getHandle().sendMessage(mc.getOriginalText());
        }
    }

    @Override
    public void broadcastSystemMessage(IComponent message) {
        if (server != null && server.getPlayerManager() != null && message instanceof MinecraftComponent mc) {
            server.getPlayerManager().broadcast(mc.getOriginalText(), false);
        }
    }

    @Override
    public void broadcastChatMessage(IComponent message) {
        if (server != null && server.getPlayerManager() != null && message instanceof MinecraftComponent mc) {
            server.getPlayerManager().broadcast(mc.getOriginalText(), false);
        }
    }

    @Override
    public void broadcastSystemMessage(IComponent message, String header, String footer, @Nullable IPlayer playerContext) {
        if (messageParser == null || server == null) return;

        if (playerContext != null) {
            IComponent headerComp = messageParser.parseMessage(header, playerContext);
            IComponent footerComp = messageParser.parseMessage(footer, playerContext);
            getOnlinePlayers().forEach(p -> {
                sendSystemMessage(p, headerComp);
                sendSystemMessage(p, message);
                sendSystemMessage(p, footerComp);
            });
        } else {
            getOnlinePlayers().forEach(p -> {
                IComponent headerComp = messageParser.parseMessage(header, p);
                IComponent footerComp = messageParser.parseMessage(footer, p);
                sendSystemMessage(p, headerComp);
                sendSystemMessage(p, message);
                sendSystemMessage(p, footerComp);
            });
        }
    }

    @Override
    public void sendTitle(IPlayer player, IComponent title, IComponent subtitle) {
        if (player instanceof MinecraftPlayer mp) {
            if (title instanceof MinecraftComponent titleMc) {
                mp.getHandle().networkHandler.sendPacket(new TitleS2CPacket(titleMc.getOriginalText()));
            }
            if (subtitle != null && subtitle instanceof MinecraftComponent subtitleMc) {
                String subStr = subtitleMc.getRawText();
                if (!subStr.isEmpty()) {
                    mp.getHandle().networkHandler.sendPacket(new SubtitleS2CPacket(subtitleMc.getOriginalText()));
                }
            }
        }
    }

    @Override
    public void sendSubtitle(IPlayer player, IComponent subtitle) {
        if (player instanceof MinecraftPlayer mp && subtitle != null && subtitle instanceof MinecraftComponent mc) {
            if (!mc.getRawText().isEmpty()) {
                mp.getHandle().networkHandler.sendPacket(new SubtitleS2CPacket(mc.getOriginalText()));
            }
        }
    }

    @Override
    public void sendActionBar(IPlayer player, IComponent message) {
        if (player instanceof MinecraftPlayer mp && message instanceof MinecraftComponent mc) {
            mp.getHandle().networkHandler.sendPacket(new OverlayMessageS2CPacket(mc.getOriginalText()));
        }
    }

    private BossBar.Color toMinecraftColor(BossBarColor color) {
        return BossBar.Color.valueOf(color.name());
    }

    private BossBar.Style toMinecraftOverlay(BossBarOverlay overlay) {
        return BossBar.Style.valueOf(overlay.name());
    }

    @Override
    public void sendBossBar(List<IPlayer> players, IComponent message, int durationSeconds, BossBarColor color, float progress) {
        if (!(message instanceof MinecraftComponent mc)) return;
        ServerBossBar bossEvent = new ServerBossBar(mc.getOriginalText(), toMinecraftColor(color), BossBar.Style.PROGRESS);
        bossEvent.setPercent(progress);
        players.forEach(p -> {
            if (p instanceof MinecraftPlayer mp) {
                bossEvent.addPlayer(mp.getHandle());
            }
        });
        taskScheduler.schedule(() -> {
            bossEvent.clearPlayers();
            bossEvent.setVisible(false);
        }, durationSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void showPersistentBossBar(IPlayer player, IComponent message, BossBarColor color, BossBarOverlay overlay) {
        if (!(player instanceof MinecraftPlayer mp) || !(message instanceof MinecraftComponent mc)) return;
        removePersistentBossBar(player);
        ServerBossBar bossEvent = new ServerBossBar(mc.getOriginalText(), toMinecraftColor(color), toMinecraftOverlay(overlay));
        bossEvent.addPlayer(mp.getHandle());
        persistentBossBars.put(player.getUUID(), bossEvent);
    }

    @Override
    public void removePersistentBossBar(IPlayer player) {
        ServerBossBar bossBar = persistentBossBars.remove(player.getUUID());
        if (bossBar != null) {
            bossBar.clearPlayers();
        }
    }

    @Override
    public void createOrUpdateRestartBossBar(IComponent message, BossBarColor color, float progress) {
        if (!(message instanceof MinecraftComponent mc)) return;
        if (restartBossBar == null) {
            restartBossBar = new ServerBossBar(mc.getOriginalText(), toMinecraftColor(color), BossBar.Style.PROGRESS);
            restartBossBar.setVisible(true);
            getOnlinePlayers().forEach(p -> {
                if (p instanceof MinecraftPlayer mp) {
                    restartBossBar.addPlayer(mp.getHandle());
                }
            });
        }
        restartBossBar.setName(mc.getOriginalText());
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
    public void clearTitles(IPlayer player) {
        if (player instanceof MinecraftPlayer mp) {
            mp.getHandle().networkHandler.sendPacket(new ClearTitleS2CPacket(true));
        }
    }

    @Override
    public void playSound(IPlayer player, String soundId, net.minecraft.sound.SoundCategory category, float volume, float pitch) {
        if (!(player instanceof MinecraftPlayer mp)) return;
        Identifier soundIdentifier = Identifier.tryParse(soundId);
        if (soundIdentifier == null) {
            soundIdentifier = Identifier.of("minecraft", soundId);
        }
        SoundEvent soundEvent = Registries.SOUND_EVENT.get(soundIdentifier);
        if (soundEvent != null) {
            ServerPlayerEntity handle = mp.getHandle();
            handle.networkHandler.sendPacket(new PlaySoundS2CPacket(
                    Registries.SOUND_EVENT.getEntry(soundEvent),
                    category,
                    handle.getX(), handle.getY(), handle.getZ(),
                    volume, pitch, handle.getWorld().getRandom().nextLong()
            ));
        }
    }

    @Override
    public void executeCommandAs(ServerCommandSource source, String command) {
        getMinecraftServer().getCommandManager().executeWithPrefix(source, command);
    }

    @Override
    public void executeCommandAsConsole(String command) {
        if (server == null) return;
        ServerCommandSource consoleSource = server.getCommandSource().withLevel(4);
        server.getCommandManager().executeWithPrefix(consoleSource, command);
    }

    @Override
    public String replacePlaceholders(String text, @Nullable IPlayer player) {
        ServerPlayerEntity serverPlayer = null;
        if (player instanceof MinecraftPlayer mp) {
            serverPlayer = mp.getHandle();
        }
        return placeholders.replacePlaceholders(text, serverPlayer);
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
        return hasPerm;
    }

    @Override
    public void shutdownServer(IComponent kickMessage) {
        if (server != null) {
            try {
                debugLogger.debugLog("PlatformAdapter: Initiating server shutdown sequence.");
                if (kickMessage instanceof MinecraftComponent mc) {
                    server.getPlayerManager().broadcast(mc.getOriginalText(), false);
                }
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
    public void sendSuccess(ServerCommandSource source, IComponent message, boolean toOps) {
        if (message instanceof MinecraftComponent mc) {
            source.sendFeedback(() -> mc.getOriginalText(), toOps);
        }
    }

    @Override
    public void sendFailure(ServerCommandSource source, IComponent message) {
        if (message instanceof MinecraftComponent mc) {
            source.sendError(mc.getOriginalText());
        }
    }

    @Override
    public void teleportPlayer(IPlayer player, double x, double y, double z) {
        if (player instanceof MinecraftPlayer mp) {
            mp.getHandle().requestTeleport(x, y, z);
        }
    }

    @Override
    public boolean playerHasItem(IPlayer player, String itemId, int amount) {
        if (player == null || itemId == null) {
            return false;
        }
        if (!(player instanceof MinecraftPlayer mp)) {
            return false;
        }
        Item item = Registries.ITEM.get(Identifier.of(itemId));
        if (item == Items.AIR) {
            debugLogger.debugLog("PlatformAdapter: Could not find item with ID: " + itemId);
            return false;
        }
        return mp.getHandle().getInventory().count(item) >= amount;
    }

    @Override
    public boolean isPlayerInArea(IPlayer player, String worldId, List<Integer> corner1, List<Integer> corner2) {
        if (player == null || worldId == null || corner1 == null || corner2 == null || corner1.size() != 3 || corner2.size() != 3) {
            return false;
        }
        if (!(player instanceof MinecraftPlayer mp)) {
            return false;
        }

        Identifier worldIdentifier = Identifier.of(worldId);
        net.minecraft.registry.RegistryKey<World> targetWorldKey = net.minecraft.registry.RegistryKey.of(RegistryKeys.WORLD, worldIdentifier);

        if (!mp.getHandle().getWorld().getRegistryKey().equals(targetWorldKey)) {
            return false;
        }

        Vec3d pos = mp.getHandle().getPos();
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
                .map(this::getPlayerName)
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

    @Override
    public ICommandSource wrapCommandSource(ServerCommandSource source) {
        return MinecraftCommandSource.of(source);
    }

    @Override
    public IComponent createEmptyComponent() {
        return new MinecraftComponent(Text.literal(""));
    }

    @Override
    public IComponent parseFormattingCode(String code, IComponent currentComponent) {
        if (code == null || code.isEmpty()) return currentComponent;

        net.minecraft.util.Formatting format = net.minecraft.util.Formatting.byCode(code.charAt(0));
        if (format != null) {
            if (format == net.minecraft.util.Formatting.RESET) {
                return currentComponent.resetStyle();
            }
            return currentComponent.withFormatting(format);
        }
        return currentComponent;
    }

    @Override
    public IComponent parseHexColor(String hex, IComponent currentComponent) {
        if (hex == null || hex.length() != 6) return currentComponent;

        try {
            return currentComponent.withColor(hex);
        } catch (Exception e) {
            debugLogger.debugLog("Failed to parse hex color: " + hex, e);
            return currentComponent;
        }
    }

    @Override
    public IComponent wrap(Text text) {
        if (text == null) {
            return createEmptyComponent();
        }
        if (text instanceof MutableText mt) {
            return new MinecraftComponent(mt);
        }
        return new MinecraftComponent(text);
    }

    @Override
    public IComponent createComponentFromLiteral(String text) {
        return new MinecraftComponent(Text.literal(text != null ? text : ""));
    }

    @Override
    public String getMinecraftVersion() {
        return net.minecraft.SharedConstants.getGameVersion().getName();
    }

    @Override
    public Style createStyleWithClickEvent(Style baseStyle, String action, String value) {
        String val = value != null ? value : "";

        ClickEvent.Action clickAction = switch (action.toUpperCase()) {
            case "OPEN_URL" -> ClickEvent.Action.OPEN_URL;
            case "RUN_COMMAND" -> ClickEvent.Action.RUN_COMMAND;
            case "SUGGEST_COMMAND" -> ClickEvent.Action.SUGGEST_COMMAND;
            case "CHANGE_PAGE" -> ClickEvent.Action.CHANGE_PAGE;
            case "COPY_TO_CLIPBOARD" -> ClickEvent.Action.COPY_TO_CLIPBOARD;
            default -> ClickEvent.Action.SUGGEST_COMMAND;
        };

        return baseStyle.withClickEvent(new ClickEvent(clickAction, val));
    }

    @Override
    public Style createStyleWithHoverEvent(Style baseStyle, Text hoverText) {
        return baseStyle.withHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                hoverText != null ? hoverText : Text.empty()
        ));
    }
}
