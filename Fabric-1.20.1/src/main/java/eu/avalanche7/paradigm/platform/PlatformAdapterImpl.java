package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
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
import net.minecraft.stat.Stats;
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
import com.mojang.brigadier.CommandDispatcher;

public class PlatformAdapterImpl implements IPlatformAdapter {

    private MinecraftServer server;
    private MessageParser messageParser;
    private PermissionsHandler permissionsHandler;
    private final Placeholders placeholders;
    private final TaskScheduler taskScheduler;
    private final DebugLogger debugLogger;
    private final Map<String, ServerBossBar> persistentBossBars = new HashMap<>();
    private ServerBossBar restartBossBar;
    private final eu.avalanche7.paradigm.platform.Interfaces.IConfig config;
    private final IEventSystem eventSystem;
    private CommandDispatcher<ServerCommandSource> commandDispatcher;

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
        this.config = new FabricConfig();
        this.eventSystem = new MinecraftEventSystem();
    }

    public void setPermissionsHandler(PermissionsHandler permissionsHandler) {
        this.permissionsHandler = permissionsHandler;
    }

    @Override
    public void provideMessageParser(MessageParser messageParser) {
        this.messageParser = messageParser;
    }

    @Override
    public Object getMinecraftServer() {
        return this.server;
    }

    @Override
    public void setMinecraftServer(Object server) {
        this.server = server instanceof MinecraftServer ms ? ms : null;
        if (this.server != null) {
            this.commandDispatcher = this.server.getCommandManager().getDispatcher();
        }
    }

    public void setCommandDispatcher(CommandDispatcher<ServerCommandSource> dispatcher) {
        this.commandDispatcher = dispatcher;
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
        Item item = Registries.ITEM.get(new Identifier("minecraft", itemId));
        return item != Items.AIR ? new ItemStack(item) : new ItemStack(Items.STONE);
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode) {
        if (player == null) return false;
        // Prefer PermissionsHandler (LuckPerms) pokud je dostupný.
        if (permissionsHandler != null) {
            try {
                return permissionsHandler.hasPermission(player, permissionNode);
            } catch (Throwable ignored) {
            }
        }

        // Fallback: vanilla op-level (bez rekurze)
        return hasPermission(player, permissionNode, 2);
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode, int vanillaLevel) {
        if (!(player instanceof MinecraftPlayer mp)) {
            return false;
        }

        // 1) LuckPerms (přes PermissionsHandler), bez rekurze
        if (permissionsHandler != null) {
            try {
                if (permissionsHandler.hasPermission(player, permissionNode)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2) Vanilla op-level
        return mp.getHandle().hasPermissionLevel(vanillaLevel);
    }

    @Override
    public void sendSystemMessage(IPlayer player, IComponent message) {
        if (player instanceof MinecraftPlayer mp && message != null) {
            Object nativeText = message.getOriginalText();
            if (nativeText instanceof net.minecraft.text.Text t) {
                mp.getHandle().sendMessage(t);
            }
        }
    }

    @Override
    public void broadcastSystemMessage(IComponent message) {
        if (server != null && server.getPlayerManager() != null && message != null) {
            Object nativeText = message.getOriginalText();
            if (nativeText instanceof net.minecraft.text.Text t) {
                server.getPlayerManager().broadcast(t, false);
            }
        }
    }

    @Override
    public void broadcastChatMessage(IComponent message) {
        if (server != null && server.getPlayerManager() != null && message != null) {
            Object nativeText = message.getOriginalText();
            if (nativeText instanceof net.minecraft.text.Text t) {
                server.getPlayerManager().broadcast(t, false);
            }
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
            if (title != null) {
                Object nativeTitle = title.getOriginalText();
                if (nativeTitle instanceof Text t) {
                    mp.getHandle().networkHandler.sendPacket(new TitleS2CPacket(t));
                }
            }
            if (subtitle != null) {
                Object nativeSub = subtitle.getOriginalText();
                if (nativeSub instanceof Text t) {
                    String subStr = subtitle.getRawText();
                    if (subStr != null && !subStr.isEmpty()) {
                        mp.getHandle().networkHandler.sendPacket(new SubtitleS2CPacket(t));
                    }
                }
            }
        }
    }

    @Override
    public void sendSubtitle(IPlayer player, IComponent subtitle) {
        if (player instanceof MinecraftPlayer mp && subtitle != null) {
            if (subtitle.getRawText() != null && !subtitle.getRawText().isEmpty()) {
                Object nativeSub = subtitle.getOriginalText();
                if (nativeSub instanceof Text t) {
                    mp.getHandle().networkHandler.sendPacket(new SubtitleS2CPacket(t));
                }
            }
        }
    }

    @Override
    public void sendActionBar(IPlayer player, IComponent message) {
        if (player instanceof MinecraftPlayer mp && message != null) {
            Object nativeMsg = message.getOriginalText();
            if (nativeMsg instanceof Text t) {
                mp.getHandle().networkHandler.sendPacket(new OverlayMessageS2CPacket(t));
            }
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
        Object nativeMsg = message != null ? message.getOriginalText() : null;
        if (!(nativeMsg instanceof Text t)) return;
        ServerBossBar bossEvent = new ServerBossBar(t, toMinecraftColor(color), BossBar.Style.PROGRESS);
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
        if (!(player instanceof MinecraftPlayer mp)) return;
        Object nativeMsg = message != null ? message.getOriginalText() : null;
        if (!(nativeMsg instanceof Text t)) return;
        removePersistentBossBar(player);
        ServerBossBar bossEvent = new ServerBossBar(t, toMinecraftColor(color), toMinecraftOverlay(overlay));
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
        Object nativeMsg = message != null ? message.getOriginalText() : null;
        if (!(nativeMsg instanceof Text t)) return;
        if (restartBossBar == null) {
            restartBossBar = new ServerBossBar(t, toMinecraftColor(color), BossBar.Style.PROGRESS);
            restartBossBar.setVisible(true);
            getOnlinePlayers().forEach(p -> {
                if (p instanceof MinecraftPlayer mp) {
                    restartBossBar.addPlayer(mp.getHandle());
                }
            });
        }
        restartBossBar.setName(t);
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
    public void playSound(IPlayer player, String soundId, String category, float volume, float pitch) {
        if (!(player instanceof MinecraftPlayer mp)) return;
        Identifier soundIdentifier = Identifier.tryParse(soundId);
        if (soundIdentifier == null) {
            soundIdentifier = new Identifier("minecraft", soundId);
        }
        SoundEvent soundEvent = Registries.SOUND_EVENT.get(soundIdentifier);
        if (soundEvent != null) {
            ServerPlayerEntity handle = mp.getHandle();
            net.minecraft.sound.SoundCategory cat;
            try {
                cat = category != null ? net.minecraft.sound.SoundCategory.valueOf(category.toUpperCase()) : net.minecraft.sound.SoundCategory.MASTER;
            } catch (IllegalArgumentException e) {
                cat = net.minecraft.sound.SoundCategory.MASTER;
            }
            handle.networkHandler.sendPacket(new PlaySoundS2CPacket(
                    Registries.SOUND_EVENT.getEntry(soundEvent),
                    cat,
                    handle.getX(), handle.getY(), handle.getZ(),
                    volume, pitch, handle.getWorld().getRandom().nextLong()
            ));
        }
    }

    @Override
    public void executeCommandAs(ICommandSource source, String command) {
        if (server == null) return;
        Object orig = source != null ? source.getOriginalSource() : null;
        if (orig instanceof ServerCommandSource scs) {
            server.getCommandManager().executeWithPrefix(scs, command);
        } else {
            executeCommandAsConsole(command);
        }
    }

    @Override
    public void executeCommandAsConsole(String command) {
        if (server == null) return;
        ServerCommandSource consoleSource = server.getCommandSource().withLevel(4);
        server.getCommandManager().executeWithPrefix(consoleSource, command);
    }

    @Override
    public String replacePlaceholders(String text, @Nullable IPlayer player) {
        return placeholders.replacePlaceholders(text, player);
    }

    @Override
    public boolean hasPermissionForCustomCommand(ICommandSource source, CustomCommand command) {
        if (!command.isRequirePermission()) {
            return true;
        }
        if (source == null) return true;
        IPlayer p = source.getPlayer();
        if (p == null || !(p instanceof MinecraftPlayer mp)) {
            return true;
        }
        if (permissionsHandler == null) {
            return false;
        }
        return permissionsHandler.hasPermission(mp, command.getPermission());
    }

    @Override
    public void shutdownServer(IComponent kickMessage) {
        if (server != null) {
            try {
                debugLogger.debugLog("PlatformAdapter: Initiating server shutdown sequence.");
                if (kickMessage != null) {
                    Object nativeMsg = kickMessage.getOriginalText();
                    if (nativeMsg instanceof Text t) {
                        server.getPlayerManager().broadcast(t, false);
                    }
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
    public void sendSuccess(ICommandSource source, IComponent message, boolean toOps) {
        Object orig = source != null ? source.getOriginalSource() : null;
        if (orig instanceof ServerCommandSource scs && message != null) {
            Object nativeMsg = message.getOriginalText();
            if (nativeMsg instanceof Text t) {
                scs.sendFeedback(() -> t, toOps);
            }
        }
    }

    @Override
    public void sendFailure(ICommandSource source, IComponent message) {
        Object orig = source != null ? source.getOriginalSource() : null;
        if (orig instanceof ServerCommandSource scs && message != null) {
            Object nativeMsg = message.getOriginalText();
            if (nativeMsg instanceof Text t) {
                scs.sendError(t);
            }
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
        Item item = Registries.ITEM.get(new Identifier("minecraft", itemId));
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

        String[] parts = worldId.split(":", 2);
        String namespace = parts.length > 1 ? parts[0] : "minecraft";
        String path = parts.length > 1 ? parts[1] : worldId;
        Identifier worldIdentifier = new Identifier(namespace, path);
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
    public IPlayer wrapPlayer(Object player) {
        if (player instanceof ServerPlayerEntity spe) {
            return MinecraftPlayer.of(spe);
        }
        if (player instanceof MinecraftPlayer mp) {
            return mp;
        }
        return null;
    }

    @Override
    public ICommandSource wrapCommandSource(Object source) {
        if (source instanceof ServerCommandSource scs) {
            return MinecraftCommandSource.of(scs);
        }
        if (source instanceof ICommandSource cs) {
            return cs;
        }
        return null;
    }

    @Override
    public IComponent createEmptyComponent() {
        return new MinecraftComponent(Text.literal(""));
    }

    @Override
    public IComponent parseFormattingCode(String code, IComponent currentComponent) {
        if (currentComponent == null) return createEmptyComponent();
        if (code == null) return currentComponent;
        net.minecraft.util.Formatting format = net.minecraft.util.Formatting.byName(code);
        if (format == null && code.length() == 1) {
            format = net.minecraft.util.Formatting.byCode(code.charAt(0));
        }
        if (format == net.minecraft.util.Formatting.RESET) {
            return currentComponent.resetStyle();
        }
        return currentComponent.withFormatting(code);
    }

    @Override
    public IComponent parseHexColor(String hex, IComponent currentComponent) {
        if (currentComponent == null) return createEmptyComponent();
        return currentComponent.withColorHex(hex);
    }

    @Override
    public IComponent wrap(Object text) {
        if (text == null) {
            return createEmptyComponent();
        }
        if (text instanceof IComponent comp) {
            return comp;
        }
        if (text instanceof Text mcText) {
            if (mcText instanceof MutableText mt) {
                return new MinecraftComponent(mt);
            }
            return new MinecraftComponent(mcText);
        }
        return createComponentFromLiteral(String.valueOf(text));
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
    public Object createStyleWithClickEvent(Object baseStyle, String action, String value) {
        Style style = baseStyle instanceof Style s ? s : Style.EMPTY;
        if (action == null) return style;

        ClickEvent click;
        switch (action) {
            case "OPEN_URL", "open_url" -> click = new ClickEvent(ClickEvent.Action.OPEN_URL, value);
            case "RUN_COMMAND", "run_cmd", "run_command" -> click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, value);
            case "SUGGEST_COMMAND", "suggest_command" -> click = new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, value);
            case "COPY_TO_CLIPBOARD", "copy_to_clipboard" -> click = new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value);
            case "CHANGE_PAGE", "change_page" -> click = new ClickEvent(ClickEvent.Action.CHANGE_PAGE, value);
            default -> click = null;
        }

        return click == null ? style : style.withClickEvent(click);
    }

    @Override
    public Object createStyleWithHoverEvent(Object baseStyle, Object hoverText) {
        Style style = baseStyle instanceof Style s ? s : Style.EMPTY;
        Text hover = hoverText instanceof Text t ? t : Text.literal(String.valueOf(hoverText));
        return style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
    }

    @Override
    public eu.avalanche7.paradigm.platform.Interfaces.IConfig getConfig() {
        return this.config;
    }

    @Override
    public IEventSystem getEventSystem() {
        return this.eventSystem;
    }

    @Override
    public eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder createCommandBuilder() {
        return new FabricCommandBuilder();
    }

    @Override
    public void registerCommand(eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder builder) {
        // Fabric registruje commandy dřív, než máme k dispozici uložený server v setMinecraftServer().
        // Proto si dispatcher vytáhneme lazy buď z uloženého commandDispatcher, nebo přímo ze serveru.
        CommandDispatcher<ServerCommandSource> dispatcher = this.commandDispatcher;
        if (dispatcher == null && this.server != null) {
            try {
                dispatcher = this.server.getCommandManager().getDispatcher();
                this.commandDispatcher = dispatcher;
            } catch (Throwable ignored) {
            }
        }
        if (dispatcher == null) {
            return;
        }

        Object built = builder.build();
        if (built instanceof com.mojang.brigadier.builder.LiteralArgumentBuilder<?> lit) {
            @SuppressWarnings("unchecked")
            com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> literalBuilder =
                (com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>) lit;
            dispatcher.register(literalBuilder);
        }
    }

    @Override
    public boolean isFirstJoin(eu.avalanche7.paradigm.platform.Interfaces.IPlayer player) {
        try {
            if (player instanceof MinecraftPlayer mp) {
                net.minecraft.server.network.ServerPlayerEntity p = mp.getHandle();
                return p.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.LEAVE_GAME)) == 0;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
