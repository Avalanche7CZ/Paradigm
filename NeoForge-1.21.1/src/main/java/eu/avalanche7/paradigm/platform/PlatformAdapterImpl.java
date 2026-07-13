package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.*;
import eu.avalanche7.paradigm.utils.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public class PlatformAdapterImpl implements IPlatformAdapter {

    private MinecraftServer server;
    private MessageParser messageParser;
    private PermissionsHandler permissionsHandler;
    private final Placeholders placeholders;
    private final TaskScheduler taskScheduler;
    private final DebugLogger debugLogger;
    private final Map<UUID, ServerBossEvent> persistentBossBars = new HashMap<>();
    private ServerBossEvent restartBossBar;
    private final MinecraftEventSystem eventSystem;
    private eu.avalanche7.paradigm.platform.Interfaces.IConfig config;

    private com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> commandDispatcher;
    private final Set<String> ownedRootsRegisteredThisCycle = new HashSet<>();

    public PlatformAdapterImpl(
            eu.avalanche7.paradigm.modules.permissions.PermissionsHandler permissionsHandler,
            eu.avalanche7.paradigm.utils.Placeholders placeholders,
            eu.avalanche7.paradigm.utils.TaskScheduler taskScheduler,
            eu.avalanche7.paradigm.utils.DebugLogger debugLogger
    ) {
        this.permissionsHandler = permissionsHandler;
        this.placeholders = placeholders;
        this.taskScheduler = taskScheduler;
        this.debugLogger = debugLogger;
        this.eventSystem = new MinecraftEventSystem();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(this.eventSystem);
        this.config = new NeoForgeConfig();
    }

    public void setPermissionsHandler(eu.avalanche7.paradigm.modules.permissions.PermissionsHandler permissionsHandler) {
        this.permissionsHandler = permissionsHandler;
    }

    public void setCommandDispatcher(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        this.commandDispatcher = dispatcher;
        this.ownedRootsRegisteredThisCycle.clear();
        try {
            if (debugLogger != null) debugLogger.debugLog("[NeoForge] CommandDispatcher set: " + (dispatcher != null));
        } catch (Throwable ignored) {}
    }

    @Override
    public eu.avalanche7.paradigm.platform.Interfaces.IConfig getConfig() {
        return config;
    }

    @Override
    public Object getCommandDispatcher() {
        return commandDispatcher;
    }

    @Override
    public ICommandBuilder createCommandBuilder() {
        return new NeoForgeCommandBuilder();
    }

    @Override
    public void registerCommand(ICommandBuilder builder) {
        Object built = builder != null ? builder.build() : null;
        if (!(built instanceof com.mojang.brigadier.builder.LiteralArgumentBuilder<?> lit)) return;

        @SuppressWarnings("unchecked")
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> cast = (com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>) lit;

        String root = null;
        try {
            root = cast.getLiteral();
        } catch (Throwable ignored) {}

        com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher = commandDispatcher;
        if (dispatcher == null && server != null) {
            dispatcher = server.getCommands().getDispatcher();
        }
        if (dispatcher == null) {
            try {
                if (debugLogger != null) debugLogger.debugLog("[NeoForge] FAILED to register command /" + (root != null ? root : "<unknown>") + ": server and dispatcher are null");
            } catch (Throwable ignored) {}
            return;
        }

        String normalizedRoot = CommandPriority.normalizeRoot(root);
        if (normalizedRoot == null) {
            return;
        }

        if (!CommandPriority.shouldRegisterRoot(normalizedRoot)) {
            return;
        }
        boolean shouldOwnRoot = CommandPriority.shouldOwnRoot(normalizedRoot);
        boolean firstParadigmRegistrationForRoot = shouldOwnRoot && ownedRootsRegisteredThisCycle.add(normalizedRoot);
        if (firstParadigmRegistrationForRoot) {
            CommandPriority.unregisterRootLiteral(dispatcher, normalizedRoot);
        }

        com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> registeredNode = dispatcher.register(cast);
        if (firstParadigmRegistrationForRoot
                && !CommandPriority.isOwnedByExpectedNode(dispatcher, normalizedRoot, registeredNode)
                && debugLogger != null) {
            debugLogger.debugLog("[Paradigm] Command root /" + normalizedRoot + " did not resolve to the newly registered Paradigm node.");
        }
        try {
            if (debugLogger != null) {
                debugLogger.debugLog("[NeoForge] Registered command: /" + normalizedRoot);
            }
        } catch (Throwable ignored) {
        }
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
        List<IPlayer> players = new ArrayList<>();
        for (ServerPlayer player : ((MinecraftServer) getMinecraftServer()).getPlayerList().getPlayers()) {
            players.add(new MinecraftPlayer(player));
        }
        return players;
    }

    @Override
    @Nullable
    public IPlayer getPlayerByName(String name) {
        ServerPlayer player = ((MinecraftServer) getMinecraftServer()).getPlayerList().getPlayerByName(name);
        return player != null ? new MinecraftPlayer(player) : null;
    }

    @Override
    @Nullable
    public IPlayer getPlayerByUuid(String uuid) {
        if (uuid == null) return null;
        try {
            java.util.UUID id = java.util.UUID.fromString(uuid);
            ServerPlayer player = ((MinecraftServer) getMinecraftServer()).getPlayerList().getPlayer(id);
            return player != null ? new MinecraftPlayer(player) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean disconnectPlayer(IPlayer player, IComponent reason) {
        if (!(player != null && player.getOriginalPlayer() instanceof ServerPlayer nativePlayer)) return false;
        Object component = reason != null ? reason.getOriginalText() : null;
        nativePlayer.connection.disconnect(component instanceof net.minecraft.network.chat.Component nativeComponent ? nativeComponent : net.minecraft.network.chat.Component.literal(reason != null ? reason.getRawText() : "Disconnected"));
        return true;
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
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        return item != null ? new ItemStack(item) : new ItemStack(Items.STONE);
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode) {
        return permissionsHandler != null && permissionsHandler.hasPermission(player, permissionNode);
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode, int vanillaLevel) {
        if (player == null) return false;
        if (hasPermission(player, permissionNode)) return true;
        if (player instanceof MinecraftPlayer mp) {
            return mp.getHandle().hasPermissions(vanillaLevel);
        }
        return false;
    }

    @Override
    public void sendSystemMessage(IPlayer player, IComponent message) {
        ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
        mcPlayer.sendSystemMessage(((MinecraftComponent) message).getHandle());
        try {
            if (debugLogger != null) debugLogger.debugLog("[NeoForge] sendSystemMessage -> " + (player != null ? player.getName() : "null") + ": " + (message != null ? message.getRawText() : "null"));
        } catch (Throwable ignored) {}
    }

    @Override
    public void broadcastSystemMessage(IComponent message) {
        if (getMinecraftServer() != null) {
            ((MinecraftServer) getMinecraftServer()).getPlayerList().broadcastSystemMessage(((MinecraftComponent) message).getHandle(), false);
            try {
                if (debugLogger != null) debugLogger.debugLog("[NeoForge] broadcastSystemMessage: " + (message != null ? message.getRawText() : "null"));
            } catch (Throwable ignored) {}
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

    public void createOrUpdateRestartBossBar(String message, BossBarColor color, float progress) {
        if (messageParser != null) {
            IComponent parsed = messageParser.parseMessage(message, null);
            createOrUpdateRestartBossBar(parsed, color, progress);
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
        if (server == null) {
            debugLogger.debugLog("PlatformAdapter: Shutdown called but server instance is null.");
            return;
        }
        try {
            if (kickMessage instanceof MinecraftComponent mc) {
                server.getPlayerList().broadcastSystemMessage(mc.getHandle(), false);
            }
            server.saveEverything(true, true, true);
            scheduleJvmExitFallback();
            server.halt(false);
        } catch (Exception e) {
            debugLogger.debugLog("PlatformAdapter: Failed to shutdown server: " + e.getMessage(), e);
            scheduleJvmExitFallback();
            try {
                server.halt(false);
            } catch (Exception ignored) {
            }
        }
    }

    private void scheduleJvmExitFallback() {
        taskScheduler.scheduleRaw(() -> {
            debugLogger.debugLog("PlatformAdapter: Forcing JVM exit with status 1 to trigger auto-restart.");
            System.exit(1);
        }, 2, TimeUnit.SECONDS);
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

    public String replacePlaceholders(String text, @Nullable IPlayer player) {
        return placeholders.replacePlaceholders(text, player);
    }

    @Override
    public boolean hasPermissionForCustomCommand(ICommandSource source, CustomCommand command) {
        if (command == null || !command.isRequirePermission()) return true;
        if (source == null) return true;
        IPlayer p = source.getPlayer();
        if (p == null) return true;
        return hasPermission(p, command.getPermission());
    }

    @Override
    public void executeOnServerThread(Runnable task) {
        if (task == null) return;
        if (server != null) server.execute(task);
        else task.run();
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
        CommandSourceStack console = server.createCommandSourceStack().withPermission(4);
        server.getCommands().performPrefixedCommand(console, command);
    }

    @Override
    public ICommandSource createCommandSourceForPlayer(IPlayer player) {
        if (player instanceof MinecraftPlayer mp) {
            return new MinecraftCommandSource(mp.getHandle().createCommandSourceStack());
        }
        return IPlatformAdapter.super.createCommandSourceForPlayer(player);
    }

    @Override
    public boolean setGameMode(IPlayer player, String mode) {
        if (!(player instanceof MinecraftPlayer mp)) {
            return false;
        }
        GameType gameType = toGameType(mode);
        return gameType != null && mp.getHandle().setGameMode(gameType);
    }

    @Override
    public boolean setMovementSpeed(IPlayer player, double baseValue) {
        if (!(player instanceof MinecraftPlayer mp) || baseValue < 0.0 || Double.isNaN(baseValue) || Double.isInfinite(baseValue)) {
            return false;
        }
        var attribute = mp.getHandle().getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null) {
            return false;
        }
        attribute.setBaseValue(baseValue);
        return true;
    }

    @Override
    public boolean healPlayer(IPlayer player) {
        if (!(player instanceof MinecraftPlayer mp)) return false;
        ServerPlayer handle = mp.getHandle();
        handle.setHealth(handle.getMaxHealth());
        return true;
    }

    @Override
    public boolean feedPlayer(IPlayer player) {
        if (!(player instanceof MinecraftPlayer mp)) return false;
        var food = mp.getHandle().getFoodData();
        food.setFoodLevel(20);
        food.setSaturation(20.0f);
        food.setExhaustion(0.0f);
        return true;
    }

    @Override
    public Boolean toggleFlight(IPlayer player) {
        if (!(player instanceof MinecraftPlayer mp)) return null;
        ServerPlayer handle = mp.getHandle();
        var abilities = handle.getAbilities();
        boolean enabled = !abilities.mayfly;
        abilities.mayfly = enabled;
        if (!enabled) abilities.flying = false;
        handle.onUpdateAbilities();
        return enabled;
    }

    @Override
    public boolean setPlayerSpeed(IPlayer player, float walkSpeed, float flySpeed, double movementBaseValue) {
        if (!(player instanceof MinecraftPlayer mp)) return false;
        ServerPlayer handle = mp.getHandle();
        handle.getAbilities().setWalkingSpeed(walkSpeed);
        handle.getAbilities().setFlyingSpeed(flySpeed);
        boolean movement = setMovementSpeed(player, movementBaseValue);
        handle.onUpdateAbilities();
        return movement;
    }

    @Override
    public boolean clearPlayerInventory(IPlayer player) {
        if (!(player instanceof MinecraftPlayer mp)) return false;
        mp.getHandle().getInventory().clearContent();
        mp.getHandle().containerMenu.broadcastChanges();
        return true;
    }

    @Override
    public boolean setPlayerInvulnerable(IPlayer player, boolean enabled) {
        if (!(player instanceof MinecraftPlayer mp)) return false;
        mp.getHandle().setInvulnerable(enabled);
        return true;
    }

    @Override
    public boolean setPlayerVanished(IPlayer player, boolean enabled) {
        if (!(player instanceof MinecraftPlayer mp)) return false;
        mp.getHandle().setInvisible(enabled);
        mp.getHandle().setSilent(enabled);
        return true;
    }

    @Override
    public List<InventoryItem> inspectPlayerInventory(IPlayer player, boolean enderChest) {
        if (!(player instanceof MinecraftPlayer mp)) return List.of();
        net.minecraft.world.Container inventory = enderChest ? mp.getHandle().getEnderChestInventory() : mp.getHandle().getInventory();
        List<InventoryItem> result = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) result.add(new InventoryItem(slot, stack.getCount(), stack.getHoverName().getString()));
        }
        return result;
    }

    @Override
    public int repairPlayerItems(IPlayer player, boolean all) {
        if (!(player instanceof MinecraftPlayer mp)) return 0;
        if (!all) {
            ItemStack stack = mp.getHandle().getMainHandItem();
            if (stack.isEmpty() || !stack.isDamageableItem() || stack.getDamageValue() == 0) return 0;
            stack.setDamageValue(0);
            return 1;
        }
        int changed = 0;
        var inventory = mp.getHandle().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.isDamageableItem() && stack.getDamageValue() > 0) {
                stack.setDamageValue(0);
                changed++;
            }
        }
        return changed;
    }

    @Override
    public boolean enchantMainHand(IPlayer player, String enchantmentId, int level) {
        if (!(player instanceof MinecraftPlayer mp) || server == null || enchantmentId == null || enchantmentId.isBlank()) return false;
        String command = "enchant " + mp.getHandle().getGameProfile().getName() + " " + enchantmentId + " " + level;
        try {
            return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack().withPermission(4)) > 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
            return false;
        }
    }

    @Override
    public Integer getHighestBlockY(IPlayer player) {
        if (!(player instanceof MinecraftPlayer mp)) return null;
        ServerPlayer handle = mp.getHandle();
        return handle.serverLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(handle.getX()), (int) Math.floor(handle.getZ()));
    }

    @Override
    public boolean jumpPlayerForward(IPlayer player, int distance) {
        if (!(player instanceof MinecraftPlayer mp)) return false;
        ServerPlayer handle = mp.getHandle();
        var look = handle.getLookAngle();
        handle.teleportTo(handle.getX() + look.x * distance, handle.getY() + look.y * distance, handle.getZ() + look.z * distance);
        return true;
    }

    @Override
    public boolean setTimeOfDay(long timeOfDay) {
        if (server == null) return false;
        boolean changed = false;
        for (ServerLevel level : server.getAllLevels()) {
            level.setDayTime(timeOfDay);
            changed = true;
        }
        return changed;
    }

    @Override
    public boolean setWeather(String weather) {
        if (server == null || weather == null) return false;
        String normalized = weather.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("clear", "sun", "rain", "thunder").contains(normalized)) return false;
        boolean raining = normalized.equals("rain") || normalized.equals("thunder");
        boolean thundering = normalized.equals("thunder");
        int clearTime = raining ? 0 : 6000;
        for (ServerLevel level : server.getAllLevels()) {
            level.setWeatherParameters(clearTime, 6000, raining, thundering);
        }
        return true;
    }

    private GameType toGameType(String mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival" -> GameType.SURVIVAL;
            case "1", "c", "creative" -> GameType.CREATIVE;
            case "2", "a", "adventure" -> GameType.ADVENTURE;
            case "3", "sp", "spectator" -> GameType.SPECTATOR;
            default -> null;
        };
    }

    @Override
    public IEventSystem getEventSystem() {
        return eventSystem;
    }

    @Override
    public List<String> getWorldNames() {
        List<String> worldNames = new ArrayList<>();
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                worldNames.add(level.dimension().location().toString());
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

    @Override
    public void sendBossBar(List<IPlayer> players, IComponent message, int durationSeconds, BossBarColor color, float progress) {
        if (players == null || players.isEmpty() || message == null) return;
        net.minecraft.network.chat.Component mcMessage = ((MinecraftComponent) message).getHandle();
        net.minecraft.world.BossEvent.BossBarColor mcColor = net.minecraft.world.BossEvent.BossBarColor.valueOf(color.name());
        net.minecraft.server.level.ServerBossEvent bossEvent = new net.minecraft.server.level.ServerBossEvent(mcMessage, mcColor, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
        bossEvent.setProgress(progress);
        players.forEach(p -> {
            if (p instanceof MinecraftPlayer mp) bossEvent.addPlayer(mp.getHandle());
        });

        taskScheduler.schedule(() -> {
            bossEvent.removeAllPlayers();
            bossEvent.setVisible(false);
        }, durationSeconds, java.util.concurrent.TimeUnit.SECONDS);
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
        return net.minecraft.SharedConstants.getCurrentVersion().getName();
    }

    @Override
    public String getLoaderName() { return "NeoForge"; }

    @Override
    public Object createStyleWithClickEvent(Object baseStyle, String action, String value) {
        if (!(baseStyle instanceof net.minecraft.network.chat.Style style)) return baseStyle;
        net.minecraft.network.chat.ClickEvent.Action a;
        try {
            a = net.minecraft.network.chat.ClickEvent.Action.valueOf(String.valueOf(action).toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable t) {
            return baseStyle;
        }
        return style.withClickEvent(new net.minecraft.network.chat.ClickEvent(a, String.valueOf(value)));
    }

    @Override
    public Object createStyleWithHoverEvent(Object baseStyle, Object hoverText) {
        if (!(baseStyle instanceof net.minecraft.network.chat.Style style)) return baseStyle;

        net.minecraft.network.chat.Component hover;
        if (hoverText instanceof net.minecraft.network.chat.Component c) {
            hover = c;
        } else if (hoverText instanceof IComponent ic && ic.getOriginalText() instanceof net.minecraft.network.chat.Component c2) {
            hover = c2;
        } else {
            hover = net.minecraft.network.chat.Component.literal(String.valueOf(hoverText));
        }

        return style.withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, hover));
    }

    @Override
    public IComponent parseFormattingCode(String code, IComponent currentComponent) {
        return currentComponent != null ? currentComponent : createEmptyComponent();
    }

    @Override
    public IComponent parseHexColor(String hex, IComponent currentComponent) {
        return currentComponent != null ? currentComponent : createEmptyComponent();
    }

    @Override
    public IPlayer wrapPlayer(Object player) {
        if (player instanceof ServerPlayer sp) {
            return MinecraftPlayer.of(sp);
        }
        throw new IllegalArgumentException("Unsupported player type: " + (player == null ? "null" : player.getClass()));
    }

    @Override
    public ICommandSource wrapCommandSource(Object source) {
        if (source instanceof CommandSourceStack stack) {
            return MinecraftCommandSource.of(stack);
        }
        throw new IllegalArgumentException("Unsupported command source type: " + (source == null ? "null" : source.getClass()));
    }

    @Override
    public void playSound(IPlayer player, String soundId, String category, float volume, float pitch) {
        if (!(player instanceof MinecraftPlayer mp)) return;
        if (soundId == null || soundId.isBlank()) return;

        ResourceLocation id;
        try {
            id = ResourceLocation.parse(soundId);
        } catch (Throwable t) {
            return;
        }

        net.minecraft.sounds.SoundEvent evt = BuiltInRegistries.SOUND_EVENT.get(id);
        if (evt == null) return;

        net.minecraft.sounds.SoundSource src;
        try {
            src = category != null ? net.minecraft.sounds.SoundSource.valueOf(category.toUpperCase(java.util.Locale.ROOT)) : net.minecraft.sounds.SoundSource.MASTER;
        } catch (Throwable t) {
            src = net.minecraft.sounds.SoundSource.MASTER;
        }

        mp.getHandle().playNotifySound(evt, src, volume, pitch);
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
    public void createOrUpdateRestartBossBar(IComponent message, BossBarColor color, float progress) {
        if (message == null) return;
        Component mcMessage = ((MinecraftComponent) message).getHandle();
        if (restartBossBar == null) {
            restartBossBar = new ServerBossEvent(mcMessage,
                    net.minecraft.world.BossEvent.BossBarColor.valueOf(color.name()),
                    net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
            restartBossBar.setVisible(true);
        }
        getOnlinePlayers().forEach(p -> {
            if (p instanceof MinecraftPlayer mp) restartBossBar.addPlayer(mp.getHandle());
        });
        restartBossBar.setName(mcMessage);
        restartBossBar.setProgress(progress);
    }

    @Override
    public void showPersistentBossBar(IPlayer player, IComponent message, BossBarColor color, BossBarOverlay overlay) {
        if (player == null || message == null) return;
        removePersistentBossBar(player);

        Component mcMessage = ((MinecraftComponent) message).getHandle();
        ServerBossEvent bar = new ServerBossEvent(
                mcMessage,
                net.minecraft.world.BossEvent.BossBarColor.valueOf(color.name()),
                net.minecraft.world.BossEvent.BossBarOverlay.valueOf(overlay.name())
        );

        if (player instanceof MinecraftPlayer mp) {
            bar.addPlayer(mp.getHandle());
        }

        try {
            persistentBossBars.put(java.util.UUID.fromString(player.getUUID()), bar);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void removePersistentBossBar(IPlayer player) {
        if (player == null) return;
        java.util.UUID id;
        try {
            id = java.util.UUID.fromString(player.getUUID());
        } catch (IllegalArgumentException e) {
            return;
        }

        ServerBossEvent bar = persistentBossBars.remove(id);
        if (bar != null && player instanceof MinecraftPlayer mp) {
            bar.removePlayer(mp.getHandle());
            bar.setVisible(false);
        }
    }

    @Override
    public void teleportPlayer(IPlayer player, double x, double y, double z) {
        if (!(player instanceof MinecraftPlayer mp)) return;
        mp.getHandle().teleportTo(x, y, z);
    }

    @Override
    public boolean teleportPlayer(IPlayer player, PlayerDataStore.StoredLocation location) {
        if (!(player instanceof MinecraftPlayer mp) || location == null || server == null) return false;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equalsIgnoreCase(location.getWorldId())) {
                mp.getHandle().teleportTo(level, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                return true;
            }
        }
        return false;
    }

    @Override
    public String getPlayerRemoteAddress(IPlayer player) {
        if (!(player instanceof MinecraftPlayer mp) || mp.getHandle().connection == null) return null;
        return String.valueOf(mp.getHandle().connection.getRemoteAddress());
    }

    @Override
    public void refreshPlayerCommandTree(IPlayer player) {
        if (server != null && player instanceof MinecraftPlayer mp) server.getCommands().sendCommands(mp.getHandle());
    }

    @Override
    public boolean playerHasItem(IPlayer player, String itemId, int amount) {
        if (!(player instanceof MinecraftPlayer mp)) return false;
        if (itemId == null) return false;

        ServerPlayer sp = mp.getHandle();
        if (sp == null) return false;

        try {
            net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
            if (item == null || item == Items.AIR) {
                return false;
            }
            int count = 0;
            for (ItemStack stack : sp.getInventory().items) {
                if (stack != null && stack.is(item)) {
                    count += stack.getCount();
                    if (count >= amount) return true;
                }
            }
            return count >= amount;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isPlayerInArea(IPlayer player, String worldId, List<Integer> corner1, List<Integer> corner2) {
        if (!(player instanceof MinecraftPlayer mp)) return false;
        if (worldId == null || corner1 == null || corner2 == null) return false;
        if (corner1.size() != 3 || corner2.size() != 3) return false;

        ServerPlayer sp = mp.getHandle();
        if (sp == null) return false;

        try {
            ResourceKey<net.minecraft.world.level.Level> targetWorldKey = ResourceKey.create(
                    Registries.DIMENSION,
                    ResourceLocation.parse(worldId)
            );

            if (!sp.level().dimension().equals(targetWorldKey)) {
                return false;
            }

            net.minecraft.world.phys.Vec3 pos = sp.position();
            double pX = pos.x();
            double pY = pos.y();
            double pZ = pos.z();

            double x1 = Math.min(corner1.get(0), corner2.get(0));
            double y1 = Math.min(corner1.get(1), corner2.get(1));
            double z1 = Math.min(corner1.get(2), corner2.get(2));
            double x2 = Math.max(corner1.get(0), corner2.get(0));
            double y2 = Math.max(corner1.get(1), corner2.get(1));
            double z2 = Math.max(corner1.get(2), corner2.get(2));

            return pX >= x1 && pX <= x2 && pY >= y1 && pY <= y2 && pZ >= z1 && pZ <= z2;
        } catch (Exception e) {
            return false;
        }
    }
}
