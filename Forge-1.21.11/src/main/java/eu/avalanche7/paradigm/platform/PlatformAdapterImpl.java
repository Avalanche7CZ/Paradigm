package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.Interfaces.*;
import eu.avalanche7.paradigm.utils.*;
import net.minecraft.advancements.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.ClickEvent;

import net.minecraft.network.chat.HoverEvent;
import net.minecraft.stats.Stats;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

public class PlatformAdapterImpl implements IPlatformAdapter {

    private MinecraftServer server;
    private MessageParser messageParser;
    private PermissionsHandler permissionsHandler;
    private final Placeholders placeholders;
    private final TaskScheduler taskScheduler;
    private final DebugLogger debugLogger;
    private final Map<UUID, ServerBossEvent> persistentBossBars = new HashMap<>();
    private ServerBossEvent restartBossBar;
    private final eu.avalanche7.paradigm.platform.Interfaces.IConfig config;
    private final IEventSystem eventSystem;
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
        MinecraftEventSystem eventSystemImpl = new MinecraftEventSystem();
        this.eventSystem = eventSystemImpl;
        eventSystemImpl.register();
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
        this.server = (MinecraftServer) server;
    }

    @Override
    public List<IPlayer> getOnlinePlayers() {
        if (server == null) {
            return java.util.Collections.emptyList();
        }
        List<IPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            players.add(new MinecraftPlayer(player));
        }
        return players;
    }

    @Override
    @Nullable
    public IPlayer getPlayerByName(String name) {
        if (server == null) return null;
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        return player != null ? new MinecraftPlayer(player) : null;
    }

    @Override
    @Nullable
    public IPlayer getPlayerByUuid(String uuid) {
        if (server == null) return null;
        try {
            UUID id = UUID.fromString(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            return player != null ? new MinecraftPlayer(player) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    public IPlayer getPlayerByUuid(UUID uuid) {
        if (server == null) return null;
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        return player != null ? new MinecraftPlayer(player) : null;
    }

    @Override
    public String getPlayerName(IPlayer player) {
        return player.getName();
    }

    @Override
    public IComponent getPlayerDisplayName(IPlayer player) {
        ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
        return new MinecraftComponent(mcPlayer.getDisplayName().copy());
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
        var item = ForgeRegistries.ITEMS.getValue(Identifier.parse(itemId));
        return item != null ? new ItemStack(item) : new ItemStack(Items.STONE);
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode) {
        if (player instanceof MinecraftPlayer mp) {
            if (permissionsHandler != null) {
                return permissionsHandler.hasPermission(mp, permissionNode);
            }
            ServerPlayer mcPlayer = mp.getHandle();
            if (mcPlayer == null || server == null) return false;
            return server.getPlayerList().isOp(new net.minecraft.server.players.NameAndId(mcPlayer.getUUID(), mcPlayer.getName().getString()));
        }
        return false;
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode, int vanillaLevel) {
        if (player instanceof MinecraftPlayer mp) {
            return this.hasPermission(player, permissionNode) || hasPermissionLevel(mp.getHandle().permissions(), vanillaLevel);
        }
        return false;
    }

    @Override
    public void sendSystemMessage(IPlayer player, IComponent message) {
        ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
        mcPlayer.sendSystemMessage(((MinecraftComponent) message).getHandle());
    }

    @Override
    public void broadcastSystemMessage(IComponent message) {
        if (getMinecraftServer() != null) {
            ((MinecraftServer) getMinecraftServer()).getPlayerList().broadcastSystemMessage(((MinecraftComponent) message).getHandle(), false);
        }
    }

    @Override
    public void broadcastChatMessage(IComponent message) {
        if (getMinecraftServer() != null) {
            ((MinecraftServer) getMinecraftServer()).getPlayerList().broadcastSystemMessage(((MinecraftComponent) message).getHandle(), false);
        }
    }

    @Override
    public void broadcastSystemMessage(IComponent message, String header, String footer, @Nullable IPlayer playerContext) {
        if (messageParser == null) return;
        IComponent headerComp = messageParser.parseMessage(header, playerContext);
        IComponent footerComp = messageParser.parseMessage(footer, playerContext);
        getOnlinePlayers().forEach(p -> {
            sendSystemMessage(p, headerComp);
            sendSystemMessage(p, message);
            sendSystemMessage(p, footerComp);
        });
    }

    @Override
    public void sendTitle(IPlayer player, IComponent title, IComponent subtitle) {
        ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
        mcPlayer.connection.send(new ClientboundSetTitleTextPacket(((MinecraftComponent) title).getHandle()));
        if (subtitle != null && !subtitle.getRawText().isEmpty()) {
            mcPlayer.connection.send(new ClientboundSetSubtitleTextPacket(((MinecraftComponent) subtitle).getHandle()));
        }
    }

    @Override
    public void sendSubtitle(IPlayer player, IComponent subtitle) {
        ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
        if (subtitle != null && !subtitle.getRawText().isEmpty()) {
            mcPlayer.connection.send(new ClientboundSetSubtitleTextPacket(((MinecraftComponent) subtitle).getHandle()));
        }
    }

    @Override
    public void sendActionBar(IPlayer player, IComponent message) {
        ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
        mcPlayer.connection.send(new ClientboundSetActionBarTextPacket(((MinecraftComponent) message).getHandle()));
    }

    @Override
    public void clearTitles(IPlayer player) {
        ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
        mcPlayer.connection.send(new ClientboundClearTitlesPacket(true));
    }

    public void sendSystemMessage(IPlayer player, String message) {
        if (messageParser != null) {
            IComponent parsed = messageParser.parseMessage(message, player);
            sendSystemMessage(player, parsed);
        }
    }

    public void sendActionBar(IPlayer player, String message) {
        if (messageParser != null) {
            ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
            IComponent parsed = messageParser.parseMessage(message, player);
            sendActionBar(player, parsed);
        }
    }

    public void sendBossBar(List<IPlayer> players, String message, int durationSeconds, BossBarColor color, float progress) {
        if (messageParser != null) {
            IComponent parsed = messageParser.parseMessage(message, !players.isEmpty() ? players.getFirst() : null);
            sendBossBar(players, parsed, durationSeconds, color, progress);
        }
    }

    private BossEvent.BossBarColor toMinecraftColor(BossBarColor color) {
        return BossEvent.BossBarColor.valueOf(color.name());
    }

    private BossEvent.BossBarOverlay toMinecraftOverlay(BossBarOverlay overlay) {
        return BossEvent.BossBarOverlay.valueOf(overlay.name());
    }

    public void sendBossBar(List<IPlayer> players, IComponent message, int durationSeconds, BossBarColor color, float progress) {
        Component mcMessage = ((MinecraftComponent) message).getHandle();
        ServerBossEvent bossEvent = new ServerBossEvent(mcMessage, toMinecraftColor(color), BossEvent.BossBarOverlay.PROGRESS);
        bossEvent.setProgress(progress);
        players.forEach(p -> bossEvent.addPlayer(((MinecraftPlayer) p).getHandle()));
        taskScheduler.schedule(() -> {
            bossEvent.removeAllPlayers();
            bossEvent.setVisible(false);
        }, durationSeconds, TimeUnit.SECONDS);
    }

    public void showPersistentBossBar(IPlayer player, IComponent message, BossBarColor color, BossBarOverlay overlay) {
        removePersistentBossBar(player);
        Component mcMessage = ((MinecraftComponent) message).getHandle();
        ServerBossEvent bossEvent = new ServerBossEvent(mcMessage, toMinecraftColor(color), toMinecraftOverlay(overlay));
        bossEvent.addPlayer(((MinecraftPlayer) player).getHandle());
        persistentBossBars.put(UUID.fromString(player.getUUID()), bossEvent);
    }

    public void removePersistentBossBar(IPlayer player) {
        ServerBossEvent bossBar = persistentBossBars.remove(UUID.fromString(player.getUUID()));
        if (bossBar != null) {
            bossBar.removePlayer(((MinecraftPlayer) player).getHandle());
        }
    }

    public void createOrUpdateRestartBossBar(IComponent message, BossBarColor color, float progress) {
        Component mcMessage = ((MinecraftComponent) message).getHandle();
        if (restartBossBar == null) {
            restartBossBar = new ServerBossEvent(mcMessage, toMinecraftColor(color), BossEvent.BossBarOverlay.PROGRESS);
            restartBossBar.setVisible(true);
            getOnlinePlayers().forEach(p -> restartBossBar.addPlayer(((MinecraftPlayer) p).getHandle()));
        }
        restartBossBar.setName(mcMessage);
        restartBossBar.setProgress(progress);
    }

    public void createOrUpdateRestartBossBar(String message, BossBarColor color, float progress) {
        if (messageParser != null) {
            IComponent parsed = messageParser.parseMessage(message, null);
            createOrUpdateRestartBossBar(parsed, color, progress);
        }
    }

    public void removeRestartBossBar() {
        if (restartBossBar != null) {
            restartBossBar.setVisible(false);
            restartBossBar.removeAllPlayers();
            restartBossBar = null;
        }
    }

    public void sendTitle(IPlayer player, String title, String subtitle) {
        if (messageParser != null) {
            IComponent titleComp = messageParser.parseMessage(title, player);
            IComponent subtitleComp = subtitle != null ? messageParser.parseMessage(subtitle, player) : createLiteralComponent("");
            sendTitle(player, titleComp, subtitleComp);
        }
    }

    @Override
    public void shutdownServer(IComponent kickMessage) {
        MinecraftServer server = (MinecraftServer) getMinecraftServer();
        if (server != null && kickMessage instanceof MinecraftComponent mc) {
            try {
                server.getPlayerList().broadcastSystemMessage(mc.getHandle(), false);
                server.saveEverything(true, true, true);
                server.halt(false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void playSound(IPlayer player, String soundId, String category, float volume, float pitch) {
        if (!(player instanceof MinecraftPlayer mp)) return;
        ServerPlayer mcPlayer = mp.getHandle();
        try {
            SoundEvent soundEvent = ForgeRegistries.SOUND_EVENTS.getValue(Identifier.parse(soundId));
            if (soundEvent == null) return;
            SoundSource mcCategory;
            try {
                mcCategory = category != null ? SoundSource.valueOf(category.toUpperCase()) : SoundSource.MASTER;
            } catch (IllegalArgumentException e) {
                mcCategory = SoundSource.MASTER;
            }
            try {
                var method = mcPlayer.getClass().getMethod("playNotifySound", SoundEvent.class, SoundSource.class, float.class, float.class);
                method.invoke(mcPlayer, soundEvent, mcCategory, volume, pitch);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                var method = mcPlayer.getClass().getMethod("playSound", SoundEvent.class, float.class, float.class);
                method.invoke(mcPlayer, soundEvent, volume, pitch);
            } catch (ReflectiveOperationException ignored) {
            }
        } catch (Exception e) {
            debugLogger.debugLog("Failed to play sound: " + soundId, e);
        }
    }

    @Override
    public ICommandSource wrapCommandSource(Object source) {
        if (source instanceof ICommandSource cs) {
            return cs;
        }
        if (source instanceof CommandSourceStack stack) {
            return new MinecraftCommandSource(stack);
        }
        return null;
    }

    @Override
    public IPlayer wrapPlayer(Object player) {
        if (player instanceof IPlayer p) {
            return p;
        }
        if (player instanceof ServerPlayer sp) {
            return new MinecraftPlayer(sp);
        }
        if (player instanceof MinecraftPlayer mp) {
            return mp;
        }
        return null;
    }

    @Override
    public void sendSuccess(ICommandSource source, IComponent message, boolean toOps) {
        if (source instanceof MinecraftCommandSource mcSource && message instanceof MinecraftComponent mc) {
            mcSource.getHandle().sendSuccess(() -> mc.getHandle(), toOps);
        }
    }

    @Override
    public void sendFailure(ICommandSource source, IComponent message) {
        if (source instanceof MinecraftCommandSource mcSource && message instanceof MinecraftComponent mc) {
            mcSource.getHandle().sendFailure(mc.getHandle());
        }
    }

    public boolean hasCommandPermission(ICommandSource source, String permission) {
        IPlayer player = source.getPlayer();
        if (player != null) {
            return hasPermission(player, permission);
        }
        return true;
    }

    @Override
    public IComponent parseFormattingCode(String code, IComponent currentComponent) {
        if (currentComponent == null) return createEmptyComponent();
        return currentComponent.withFormatting(code);
    }

    @Override
    public IComponent parseHexColor(String hex, IComponent currentComponent) {
        if (currentComponent == null) return createEmptyComponent();
        return currentComponent.withColorHex(hex);
    }

    @Override
    public Object createStyleWithClickEvent(Object baseStyle, String action, String value) {
        net.minecraft.network.chat.Style style = baseStyle instanceof net.minecraft.network.chat.Style s ? s : net.minecraft.network.chat.Style.EMPTY;
        String val = value != null ? value : "";

        try {
            String a = action != null ? action.toUpperCase() : "";
            Object click;
            switch (a) {
                case "OPEN_URL" -> {
                    String url = val.startsWith("http://") || val.startsWith("https://") ? val : "https://" + val;
                    click = instantiateNested(ClickEvent.class, "OpenUrl", java.net.URI.class, java.net.URI.create(url));
                }
                case "RUN_COMMAND", "RUN_CMD", "RUN_COMMAND_ALT" -> click = instantiateNested(ClickEvent.class, "RunCommand", String.class, val);
                case "SUGGEST_COMMAND" -> click = instantiateNested(ClickEvent.class, "SuggestCommand", String.class, val);
                case "CHANGE_PAGE" -> {
                    int page;
                    try { page = Integer.parseInt(val); } catch (NumberFormatException e) { page = 1; }
                    click = instantiateNested(ClickEvent.class, "ChangePage", int.class, page);
                }
                case "COPY_TO_CLIPBOARD" -> click = instantiateNested(ClickEvent.class, "CopyToClipboard", String.class, val);
                default -> click = instantiateNested(ClickEvent.class, "SuggestCommand", String.class, val);
            }
            if (click instanceof ClickEvent ce) {
                return style.withClickEvent(ce);
            }
        } catch (Throwable ignored) {}

        try {
            ClickEvent.Action clickAction = switch (action != null ? action.toUpperCase() : "") {
                case "OPEN_URL" -> ClickEvent.Action.OPEN_URL;
                case "RUN_COMMAND", "RUN_CMD", "RUN_COMMAND_ALT" -> ClickEvent.Action.RUN_COMMAND;
                case "SUGGEST_COMMAND" -> ClickEvent.Action.SUGGEST_COMMAND;
                case "CHANGE_PAGE" -> ClickEvent.Action.CHANGE_PAGE;
                case "COPY_TO_CLIPBOARD" -> ClickEvent.Action.COPY_TO_CLIPBOARD;
                default -> ClickEvent.Action.SUGGEST_COMMAND;
            };
            ClickEvent clickEvent = createClickEvent(clickAction, val);
            return clickEvent != null ? style.withClickEvent(clickEvent) : style;
        } catch (Throwable t) {
            return style;
        }
    }

    @Override
    public Object createStyleWithHoverEvent(Object baseStyle, Object hoverText) {
        net.minecraft.network.chat.Style style = baseStyle instanceof net.minecraft.network.chat.Style s ? s : net.minecraft.network.chat.Style.EMPTY;
        Component hover = hoverText instanceof Component c ? c : Component.literal(String.valueOf(hoverText));

        try {
            Object showText = instantiateNested(HoverEvent.class, "ShowText", Component.class, hover);
            if (showText instanceof HoverEvent he) {
                return style.withHoverEvent(he);
            }
        } catch (Throwable ignored) {}

        HoverEvent hoverEvent = createShowTextHoverEvent(hover);
        return hoverEvent != null ? style.withHoverEvent(hoverEvent) : style;
    }

    private static Object instantiateNested(Class<?> outer, String simpleName, Class<?> paramType, Object arg) throws Exception {
        for (Class<?> c : outer.getDeclaredClasses()) {
            if (c.getSimpleName().equals(simpleName)) {
                var ctor = c.getDeclaredConstructor(paramType);
                ctor.setAccessible(true);
                return ctor.newInstance(arg);
            }
        }
        throw new ClassNotFoundException(outer.getName() + "$" + simpleName);
    }

    private static ClickEvent createClickEvent(ClickEvent.Action action, String value) {
        String simpleName = switch (action) {
            case RUN_COMMAND -> "RunCommand";
            case SUGGEST_COMMAND -> "SuggestCommand";
            case OPEN_URL -> "OpenUrl";
            case COPY_TO_CLIPBOARD -> "CopyToClipboard";
            case CHANGE_PAGE -> "ChangePage";
            default -> null;
        };
        if (simpleName == null) return null;

        try {
            for (Class<?> nested : ClickEvent.class.getDeclaredClasses()) {
                if (!nested.getSimpleName().equals(simpleName)) continue;
                try {
                    var ctor = nested.getDeclaredConstructor(String.class);
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance(value);
                    if (instance instanceof ClickEvent clickEvent) {
                        return clickEvent;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
                try {
                    var ctor = nested.getDeclaredConstructor(java.net.URI.class);
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance(java.net.URI.create(value));
                    if (instance instanceof ClickEvent clickEvent) {
                        return clickEvent;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
                try {
                    var ctor = nested.getDeclaredConstructor(int.class);
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance(Integer.parseInt(value));
                    if (instance instanceof ClickEvent clickEvent) {
                        return clickEvent;
                    }
                } catch (ReflectiveOperationException | NumberFormatException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static HoverEvent createShowTextHoverEvent(Component hover) {
        try {
            Object showText = instantiateNested(HoverEvent.class, "ShowText", Component.class, hover);
            if (showText instanceof HoverEvent hoverEvent) {
                return hoverEvent;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean hasPermissionLevel(Object permissionSet, int level) {
        if (permissionSet == null) return false;
        try {
            if (permissionSet instanceof net.minecraft.server.permissions.PermissionSet ps) {
                net.minecraft.server.permissions.PermissionLevel permLevel =
                        net.minecraft.server.permissions.PermissionLevel.byId(level);
                net.minecraft.server.permissions.Permission required =
                        new net.minecraft.server.permissions.Permission.HasCommandLevel(permLevel);
                return ps.hasPermission(required);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Object instantiateNested(Class<?> outer, String simpleName, Class<?> paramType, int arg) throws Exception {
        for (Class<?> c : outer.getDeclaredClasses()) {
            if (c.getSimpleName().equals(simpleName)) {
                var ctor = c.getDeclaredConstructor(paramType);
                ctor.setAccessible(true);
                return ctor.newInstance(arg);
            }
        }
        throw new ClassNotFoundException(outer.getName() + "$" + simpleName);
    }

    @Override
    public IConfig getConfig() {
        return this.config;
    }

    @Override
    public IEventSystem getEventSystem() {
        return this.eventSystem;
    }

    @Override
    public List<String> getWorldNames() {
        List<String> worldNames = new ArrayList<>();
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                worldNames.add(level.dimension().registry().toString());
            }
        }
        return worldNames;
    }

    @Override
    public List<String> getOnlinePlayerNames() {
        List<String> playerNames = new ArrayList<>();
        for (IPlayer player : getOnlinePlayers()) {
            playerNames.add(player.getName());
        }
        return playerNames;
    }

    /**
     * Volá se z Forge {@code RegisterCommandsEvent}, kdy ještě nemusí být nastavený {@link #server}.
     * Uložíme si dispatcher a {@link #registerCommand(ICommandBuilder)} ho použije.
     */
    public void setCommandDispatcher(Object dispatcher) {
        if (dispatcher instanceof CommandDispatcher<?> cd) {
            @SuppressWarnings("unchecked")
            CommandDispatcher<CommandSourceStack> cast = (CommandDispatcher<CommandSourceStack>) cd;
            this.commandDispatcher = cast;
        }
    }

    @Override
    public void registerCommand(eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder builder) {
        Object built = builder != null ? builder.build() : null;
        if (!(built instanceof LiteralArgumentBuilder<?> lit)) {
            return;
        }

        @SuppressWarnings("unchecked")
        LiteralArgumentBuilder<CommandSourceStack> literalBuilder = (LiteralArgumentBuilder<CommandSourceStack>) lit;

        if (commandDispatcher != null) {
            commandDispatcher.register(literalBuilder);
            return;
        }

        if (server != null) {
            server.getCommands().getDispatcher().register(literalBuilder);
        }
    }

    @Override
    public ICommandBuilder createCommandBuilder() {
        return new ForgeCommandBuilder();
    }

    @Override
    public IComponent createEmptyComponent() {
        return new MinecraftComponent(Component.literal(""));
    }

    @Override
    public IComponent wrap(Object text) {
        if (text == null) return createEmptyComponent();
        if (text instanceof IComponent c) return c;
        if (text instanceof Component c) return new MinecraftComponent(c);
        return createComponentFromLiteral(String.valueOf(text));
    }

    @Override
    public IComponent createComponentFromLiteral(String text) {
        return new MinecraftComponent(Component.literal(text != null ? text : ""));
    }

    @Override
    public String getMinecraftVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().name();
    }

    @Override
    public void executeCommandAs(ICommandSource source, String command) {
        if (server == null || command == null) return;
        Object orig = source != null ? source.getOriginalSource() : null;
        if (orig instanceof CommandSourceStack css) {
            server.getCommands().performPrefixedCommand(css, command);
        } else {
            executeCommandAsConsole(command);
        }
    }

    @Override
    public void executeCommandAsConsole(String command) {
        if (server == null || command == null) return;
        CommandSourceStack console = server.createCommandSourceStack();
        server.getCommands().performPrefixedCommand(console, command);
    }

    @Override
    public String replacePlaceholders(String text, @Nullable IPlayer player) {
        return placeholders.replacePlaceholders(text, player);
    }

    @Override
    public boolean hasPermissionForCustomCommand(ICommandSource source, CustomCommand command) {
        if (command == null || !command.isRequirePermission()) return true;
        if (source == null) return true;
        IPlayer p = source.getPlayer();
        if (!(p instanceof MinecraftPlayer mp)) return true;
        return hasPermission(mp, command.getPermission());
    }

    @Override
    public void teleportPlayer(IPlayer player, double x, double y, double z) {
        if (!(player instanceof MinecraftPlayer mp)) return;
        ServerPlayer sp = mp.getHandle();
        if (sp == null) return;
        sp.teleportTo(x, y, z);
    }

    @Override
    public boolean playerHasItem(IPlayer player, String itemId, int amount) {
        if (!(player instanceof MinecraftPlayer mp)) return false;
        if (itemId == null) return false;

        ServerPlayer sp = mp.getHandle();
        if (sp == null) return false;

        try {
            Item item = ForgeRegistries.ITEMS.getValue(Identifier.parse(itemId));
            if (item == null || item == Items.AIR) {
                debugLogger.debugLog("PlatformAdapter: Could not find item with ID: " + itemId);
                return false;
            }
            int count = 0;
            for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
                ItemStack stack = sp.getInventory().getItem(i);
                if (stack != null && stack.is(item)) {
                    count += stack.getCount();
                    if (count >= amount) return true;
                }
            }
            return count >= amount;
        } catch (Exception e) {
            debugLogger.debugLog("PlatformAdapter: Failed playerHasItem for " + itemId, e);
            return false;
        }
    }

    @Override
    public boolean isPlayerInArea(IPlayer player, String worldId, List<Integer> corner1, List<Integer> corner2) {
        if (!(player instanceof MinecraftPlayer mp)) return false;
        if (worldId == null || corner1 == null || corner2 == null || corner1.size() != 3 || corner2.size() != 3) return false;

        ServerPlayer sp = mp.getHandle();
        if (sp == null) return false;

        Identifier worldLoc;
        try {
            worldLoc = Identifier.parse(worldId);
        } catch (Exception e) {
            return false;
        }

        ResourceKey<Level> targetWorld = ResourceKey.create(Registries.DIMENSION, worldLoc);
        if (sp.level().dimension() != targetWorld) {
            return false;
        }

        var pos = sp.position();
        double pX = pos.x;
        double pY = pos.y;
        double pZ = pos.z;

        double x1 = Math.min(corner1.get(0), corner2.get(0));
        double y1 = Math.min(corner1.get(1), corner2.get(1));
        double z1 = Math.min(corner1.get(2), corner2.get(2));
        double x2 = Math.max(corner1.get(0), corner2.get(0));
        double y2 = Math.max(corner1.get(1), corner2.get(1));
        double z2 = Math.max(corner1.get(2), corner2.get(2));

        return pX >= x1 && pX <= x2 && pY >= y1 && pY <= y2 && pZ >= z1 && pZ <= z2;
    }

    @Override
    public boolean isFirstJoin(IPlayer player) {
        try {
            if (player instanceof MinecraftPlayer mp) {
                ServerPlayer sp = mp.getHandle();
                if (sp == null) return false;
                return sp.getStats().getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) == 0;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public int getMaxPlayers() {
        if (server == null) return 0;
        try {
            return server.getMaxPlayers();
        } catch (Throwable t) {
            return 0;
        }
    }
}
