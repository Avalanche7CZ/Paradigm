package eu.avalanche7.paradigm.platform;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.Interfaces.*;
import eu.avalanche7.paradigm.utils.*;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class PlatformAdapterImpl implements IPlatformAdapter {

    private MinecraftServer server;
    private MessageParser messageParser;

    private PermissionsHandler permissionsHandler;
    private final Placeholders placeholders;
    private final TaskScheduler taskScheduler;
    private final DebugLogger debugLogger;

    private final Map<UUID, ServerBossEvent> persistentBossBars = new HashMap<>();
    private @Nullable ServerBossEvent restartBossBar;

    private final IConfig config;
    private final MinecraftEventSystem eventSystem;

    private CommandDispatcher<CommandSourceStack> commandDispatcher;

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

        this.config = new ForgeConfig();
        this.eventSystem = new MinecraftEventSystem();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this.eventSystem);
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
        return server;
    }

    @Override
    public void setMinecraftServer(Object server) {
        this.server = (MinecraftServer) server;
    }

    @Override
    public List<IPlayer> getOnlinePlayers() {
        if (server == null) return java.util.Collections.emptyList();
        List<IPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            out.add(new MinecraftPlayer(p));
        }
        return out;
    }

    @Override
    public @Nullable IPlayer getPlayerByName(String name) {
        if (server == null) return null;
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        return p != null ? new MinecraftPlayer(p) : null;
    }

    @Override
    public @Nullable IPlayer getPlayerByUuid(String uuid) {
        if (server == null) return null;
        try {
            UUID id = UUID.fromString(uuid);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            return p != null ? new MinecraftPlayer(p) : null;
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
        ServerPlayer mc = ((MinecraftPlayer) player).getHandle();
        return new MinecraftComponent(mc.getDisplayName().copy());
    }

    @Override
    public IComponent createLiteralComponent(String text) {
        return new MinecraftComponent(Component.literal(text));
    }

    @Override
    public IComponent createTranslatableComponent(String key, Object... args) {
        return new MinecraftComponent(Component.translatable(key, args));
    }

    @Override
    public Object createItemStack(String itemId) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        return item != null ? new ItemStack(item) : new ItemStack(Items.STONE);
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode) {
        return permissionsHandler != null && permissionsHandler.hasPermission(player, permissionNode);
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode, int vanillaLevel) {
        if (hasPermission(player, permissionNode)) return true;
        ServerPlayer mc = ((MinecraftPlayer) player).getHandle();
        return mc.hasPermissions(vanillaLevel);
    }

    @Override
    public void sendSystemMessage(IPlayer player, IComponent message) {
        ServerPlayer mc = ((MinecraftPlayer) player).getHandle();
        mc.sendSystemMessage(((MinecraftComponent) message).getHandle());
    }

    @Override
    public void broadcastSystemMessage(IComponent message) {
        if (server == null) return;
        server.getPlayerList().broadcastSystemMessage(((MinecraftComponent) message).getHandle(), false);
    }

    @Override
    public void broadcastChatMessage(IComponent message) {
        broadcastSystemMessage(message);
    }

    @Override
    public void broadcastSystemMessage(IComponent message, String header, String footer, @Nullable IPlayer player) {
        if (messageParser == null) return;
        IComponent h = messageParser.parseMessage(header, player);
        IComponent f = messageParser.parseMessage(footer, player);
        for (IPlayer p : getOnlinePlayers()) {
            sendSystemMessage(p, h);
            sendSystemMessage(p, message);
            sendSystemMessage(p, f);
        }
    }

    @Override
    public void sendTitle(IPlayer player, IComponent title, IComponent subtitle) {
        ServerPlayer mc = ((MinecraftPlayer) player).getHandle();
        mc.connection.send(new ClientboundSetTitleTextPacket(((MinecraftComponent) title).getHandle()));
        if (subtitle != null && !subtitle.getRawText().isEmpty()) {
            mc.connection.send(new ClientboundSetSubtitleTextPacket(((MinecraftComponent) subtitle).getHandle()));
        }
    }

    @Override
    public void sendSubtitle(IPlayer player, IComponent subtitle) {
        ServerPlayer mc = ((MinecraftPlayer) player).getHandle();
        if (subtitle != null && !subtitle.getRawText().isEmpty()) {
            mc.connection.send(new ClientboundSetSubtitleTextPacket(((MinecraftComponent) subtitle).getHandle()));
        }
    }

    @Override
    public void sendActionBar(IPlayer player, IComponent message) {
        ServerPlayer mc = ((MinecraftPlayer) player).getHandle();
        mc.connection.send(new ClientboundSetActionBarTextPacket(((MinecraftComponent) message).getHandle()));
    }

    @Override
    public void sendBossBar(List<IPlayer> players, IComponent message, int durationSeconds, BossBarColor color, float progress) {
        if (players == null || players.isEmpty()) return;
        Component mcMsg = ((MinecraftComponent) message).getHandle();
        ServerBossEvent event = new ServerBossEvent(mcMsg, BossEvent.BossBarColor.valueOf(color.name()), BossEvent.BossBarOverlay.PROGRESS);
        event.setProgress(progress);
        for (IPlayer p : players) {
            event.addPlayer(((MinecraftPlayer) p).getHandle());
        }
        taskScheduler.schedule(() -> {
            event.removeAllPlayers();
            event.setVisible(false);
        }, durationSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void showPersistentBossBar(IPlayer player, IComponent message, BossBarColor color, BossBarOverlay overlay) {
        removePersistentBossBar(player);
        Component mcMsg = ((MinecraftComponent) message).getHandle();
        ServerBossEvent event = new ServerBossEvent(mcMsg, BossEvent.BossBarColor.valueOf(color.name()), BossEvent.BossBarOverlay.valueOf(overlay.name()));
        event.addPlayer(((MinecraftPlayer) player).getHandle());
        persistentBossBars.put(UUID.fromString(player.getUUID()), event);
    }

    @Override
    public void removePersistentBossBar(IPlayer player) {
        ServerBossEvent event = persistentBossBars.remove(UUID.fromString(player.getUUID()));
        if (event != null) {
            event.removePlayer(((MinecraftPlayer) player).getHandle());
        }
    }

    @Override
    public void createOrUpdateRestartBossBar(IComponent message, BossBarColor color, float progress) {
        Component mcMsg = ((MinecraftComponent) message).getHandle();
        if (restartBossBar == null) {
            restartBossBar = new ServerBossEvent(mcMsg, BossEvent.BossBarColor.valueOf(color.name()), BossEvent.BossBarOverlay.PROGRESS);
            restartBossBar.setVisible(true);
            for (IPlayer p : getOnlinePlayers()) {
                restartBossBar.addPlayer(((MinecraftPlayer) p).getHandle());
            }
        }
        restartBossBar.setName(mcMsg);
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
    public void clearTitles(IPlayer player) {
        ServerPlayer mc = ((MinecraftPlayer) player).getHandle();
        mc.connection.send(new ClientboundClearTitlesPacket(true));
    }

    @Override
    public void playSound(IPlayer player, String soundId, String category, float volume, float pitch) {
        ServerPlayer mc = ((MinecraftPlayer) player).getHandle();
        SoundEvent ev = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(soundId));
        if (ev == null) return;
        SoundSource src;
        try {
            src = category != null ? SoundSource.valueOf(category.toUpperCase(java.util.Locale.ROOT)) : SoundSource.MASTER;
        } catch (IllegalArgumentException e) {
            src = SoundSource.MASTER;
        }
        mc.playNotifySound(ev, src, volume, pitch);
    }

    private void executeCommandAsStack(CommandSourceStack stack, String command) {
        if (server == null || stack == null) return;
        var dispatcher = server.getCommands().getDispatcher();
        var parse = dispatcher.parse(command, stack);
        try {
            dispatcher.execute(parse);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            debugLogger.debugLog("Command execution failed: " + command, e);
        }
    }

    @Override
    public void executeCommandAs(ICommandSource source, String command) {
        if (source instanceof MinecraftCommandSource mc) {
            executeCommandAsStack(mc.getHandle(), command);
        }
    }

    @Override
    public void executeCommandAsConsole(String command) {
        if (server == null) return;
        executeCommandAsStack(server.createCommandSourceStack(), command);
    }

    @Override
    public String replacePlaceholders(String text, @Nullable IPlayer player) {
        return placeholders.replacePlaceholders(text, player);
    }

    @Override
    public boolean hasPermissionForCustomCommand(ICommandSource source, CustomCommand command) {
        if (!command.isRequirePermission()) return true;
        IPlayer p = source.getPlayer();
        if (p == null) return true;
        return hasPermission(p, command.getPermission());
    }

    @Override
    public void shutdownServer(IComponent kickMessage) {
        if (server == null) return;
        broadcastSystemMessage(kickMessage);
        server.saveEverything(true, true, true);
        server.halt(false);
    }

    @Override
    public void sendSuccess(ICommandSource source, IComponent message, boolean toOps) {
        if (source instanceof MinecraftCommandSource mc && message instanceof MinecraftComponent m) {
            mc.getHandle().sendSuccess(m.getHandle(), toOps);
        }
    }

    @Override
    public void sendFailure(ICommandSource source, IComponent message) {
        if (source instanceof MinecraftCommandSource mc && message instanceof MinecraftComponent m) {
            mc.getHandle().sendFailure(m.getHandle());
        }
    }

    @Override
    public void teleportPlayer(IPlayer player, double x, double y, double z) {
        ((MinecraftPlayer) player).getHandle().teleportTo(x, y, z);
    }

    @Override
    public boolean playerHasItem(IPlayer player, String itemId, int amount) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        if (item == null || item == Items.AIR) return false;
        return ((MinecraftPlayer) player).getHandle().getInventory().countItem(item) >= amount;
    }

    @Override
    public boolean isPlayerInArea(IPlayer player, String worldId, List<Integer> corner1, List<Integer> corner2) {
        ServerPlayer mc = ((MinecraftPlayer) player).getHandle();
        if (worldId == null || corner1 == null || corner2 == null || corner1.size() != 3 || corner2.size() != 3) return false;

        ResourceLocation dim = mc.getLevel().dimension().location();
        if (!dim.toString().equals(worldId)) return false;

        Vec3 pos = mc.position();
        int x = (int) Math.floor(pos.x());
        int y = (int) Math.floor(pos.y());
        int z = (int) Math.floor(pos.z());

        int x1 = Math.min(corner1.get(0), corner2.get(0));
        int y1 = Math.min(corner1.get(1), corner2.get(1));
        int z1 = Math.min(corner1.get(2), corner2.get(2));
        int x2 = Math.max(corner1.get(0), corner2.get(0));
        int y2 = Math.max(corner1.get(1), corner2.get(1));
        int z2 = Math.max(corner1.get(2), corner2.get(2));

        return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    @Override
    public List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (IPlayer p : getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    @Override
    public List<String> getWorldNames() {
        if (server == null) return java.util.Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            names.add(level.dimension().location().toString());
        }
        return names;
    }

    @Override
    public IPlayer wrapPlayer(Object player) {
        return player instanceof ServerPlayer sp ? new MinecraftPlayer(sp) : null;
    }

    @Override
    public ICommandSource wrapCommandSource(Object source) {
        return source instanceof CommandSourceStack stack ? new MinecraftCommandSource(stack) : null;
    }

    @Override
    public IComponent createEmptyComponent() {
        return new MinecraftComponent(Component.literal(""));
    }

    @Override
    public IComponent parseFormattingCode(String code, IComponent currentComponent) {
        return currentComponent != null ? currentComponent.withFormatting(code) : createEmptyComponent().withFormatting(code);
    }

    @Override
    public IComponent parseHexColor(String hex, IComponent currentComponent) {
        return currentComponent != null ? currentComponent.withColorHex(hex) : createEmptyComponent().withColorHex(hex);
    }

    @Override
    public IComponent wrap(Object text) {
        if (text instanceof MinecraftComponent mc) return mc;
        if (text instanceof Component c) return new MinecraftComponent(c);
        return createLiteralComponent(String.valueOf(text));
    }

    @Override
    public IComponent createComponentFromLiteral(String text) {
        return createLiteralComponent(text);
    }

    @Override
    public String getMinecraftVersion() {
        return server != null ? server.getServerVersion() : "";
    }

    @Override
    public Object createStyleWithClickEvent(Object baseStyle, String action, String value) {
        if (baseStyle instanceof net.minecraft.network.chat.Style s) {
            net.minecraft.network.chat.ClickEvent.Action act;
            try {
                act = net.minecraft.network.chat.ClickEvent.Action.valueOf(action.toUpperCase(java.util.Locale.ROOT));
            } catch (Exception e) {
                act = net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND;
            }
            return s.withClickEvent(new net.minecraft.network.chat.ClickEvent(act, value));
        }
        return baseStyle;
    }

    @Override
    public Object createStyleWithHoverEvent(Object baseStyle, Object hoverText) {
        if (baseStyle instanceof net.minecraft.network.chat.Style s) {
            Component hover = hoverText instanceof Component c ? c : Component.literal(String.valueOf(hoverText));
            return s.withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, hover));
        }
        return baseStyle;
    }

    @Override
    public IConfig getConfig() {
        return config;
    }

    @Override
    public IEventSystem getEventSystem() {
        return eventSystem;
    }

    @Override
    public ICommandBuilder createCommandBuilder() {
        return new ForgeCommandBuilder();
    }

    // Not part of common IPlatformAdapter; called from RegisterCommandsEvent.
    public void setCommandDispatcher(Object dispatcher) {
        if (dispatcher instanceof CommandDispatcher<?> cd) {
            @SuppressWarnings("unchecked")
            CommandDispatcher<CommandSourceStack> cast = (CommandDispatcher<CommandSourceStack>) cd;
            this.commandDispatcher = cast;
        }
    }

    @Override
    public void registerCommand(ICommandBuilder builder) {
        Object b = builder != null ? builder.build() : null;
        if (b instanceof com.mojang.brigadier.builder.LiteralArgumentBuilder<?> lit) {
            @SuppressWarnings("unchecked")
            com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> l = (com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>) lit;
            if (commandDispatcher != null) {
                commandDispatcher.register(l);
                return;
            }
            if (server != null) {
                server.getCommands().getDispatcher().register(l);
            }
        }
    }
}
