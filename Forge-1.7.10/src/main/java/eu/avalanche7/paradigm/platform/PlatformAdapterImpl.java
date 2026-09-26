package eu.avalanche7.paradigm.platform;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;

public final class PlatformAdapterImpl implements IPlatformAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformAdapterImpl.class);
    private final ForgeConfig config;
    private final MinecraftEventSystem events = new MinecraftEventSystem();
    private final TaskScheduler scheduler;
    private final DebugLogger debugLogger;
    private Placeholders placeholders;
    private PermissionsHandler permissionsHandler;
    private final ConcurrentLinkedQueue<Runnable> mainTasks = new ConcurrentLinkedQueue<>();
    private final Set<String> commandCollisions = new HashSet<>();
    private final Set<ForgeCommand> ownedCommands = new LinkedHashSet<>();
    private final Map<Object, Runnable> commandContributors = new LinkedHashMap<>();
    private Object registeringContributor;
    private MinecraftServer server;
    private MessageParser parser;

    public PlatformAdapterImpl(PermissionsHandler permissionsHandler, Placeholders placeholders,
            TaskScheduler scheduler, DebugLogger debugLogger, ForgeConfig config) {
        this.permissionsHandler = permissionsHandler;
        this.placeholders = placeholders;
        this.scheduler = scheduler;
        this.debugLogger = debugLogger;
        this.config = config;
    }

    public void setPermissionsHandler(PermissionsHandler permissionsHandler) {
        this.permissionsHandler = permissionsHandler;
    }

    public void setPlaceholders(Placeholders placeholders) {
        this.placeholders = placeholders;
    }

    public void tick() {
        for (int i = 0; i < 100; i++) {
            Runnable task = mainTasks.poll();
            if (task == null)
                break;
            try {
                task.run();
            } catch (RuntimeException failure) {
                LOGGER.error("Paradigm server task failed", failure);
            }
        }
    }

    public void clear() {
        mainTasks.clear();
        commandContributors.clear();
        ownedCommands.clear();
        registeringContributor = null;
        server = null;
    }

    public void registerCommandContributor(Object contributor, Runnable registration) {
        if (contributor == null || registration == null) {
            throw new IllegalArgumentException("Command contributor and registration are required");
        }
        Runnable previous = commandContributors.put(contributor, registration);
        if (previous == null) {
            registerContributor(contributor, registration);
        } else {
            refreshRegisteredCommandContributors();
        }
    }

    private void registerContributor(Object contributor, Runnable registration) {
        Object previous = registeringContributor;
        registeringContributor = contributor;
        try {
            registration.run();
        } finally {
            registeringContributor = previous;
        }
    }

    public MinecraftEventSystem events() {
        return events;
    }

    private static EntityPlayerMP nativePlayer(IPlayer player) {
        if (player == null || !(player.getOriginalPlayer() instanceof EntityPlayerMP))
            throw new IllegalArgumentException("Expected an online 1.7.10 player");
        return (EntityPlayerMP) player.getOriginalPlayer();
    }

    private static IChatComponent nativeText(IComponent text) {
        if (text == null || !(text.getOriginalText() instanceof IChatComponent))
            throw new IllegalArgumentException("Expected a 1.7.10 component");
        return (IChatComponent) text.getOriginalText();
    }

    private static UnsupportedOperationException unsupported(String capability) {
        return new UnsupportedOperationException(
                "Forge 1.7.10 milestone does not implement " + capability);
    }

    @Override
    public void provideMessageParser(MessageParser value) {
        parser = value;
    }

    @Override
    public Object getMinecraftServer() {
        return server;
    }

    @Override
    public void setMinecraftServer(Object value) {
        server = (MinecraftServer) value;
    }

    @Override
    public List<IPlayer> getOnlinePlayers() {
        List<IPlayer> players = new ArrayList<>();
        if (server != null)
            for (Object player : server.getConfigurationManager().playerEntityList)
                players.add(new MinecraftPlayer((EntityPlayerMP) player));
        return players;
    }

    @Override
    public IPlayer getPlayerByName(String name) {
        EntityPlayerMP player =
                server == null ? null : server.getConfigurationManager().func_152612_a(name);
        return player == null ? null : new MinecraftPlayer(player);
    }

    @Override
    public IPlayer getPlayerByUuid(String uuid) {
        for (IPlayer player : getOnlinePlayers())
            if (player.getUUID().equalsIgnoreCase(uuid))
                return player;
        return null;
    }

    @Override
    public String getPlayerName(IPlayer player) {
        return player.getName();
    }

    @Override
    public IComponent getPlayerDisplayName(IPlayer player) {
        return new MinecraftComponent(nativePlayer(player).getDisplayName());
    }

    @Override
    public IComponent createLiteralComponent(String text) {
        return new MinecraftComponent(text);
    }

    @Override
    public IComponent createTranslatableComponent(String key, Object... args) {
        return new MinecraftComponent(new ChatComponentTranslation(key, args));
    }

    @Override
    public Object createItemStack(String itemId) {
        Item item = (Item) Item.itemRegistry.getObject(itemId);
        return item == null ? null : new ItemStack(item);
    }

    @Override
    public boolean hasPermission(IPlayer player, String node) {
        if (permissionsHandler == null) {
            debugLogger.debugLog("Forge 1.7.10 permission check before runtime binding: " + node);
            return false;
        }
        return permissionsHandler.hasPermission(player, node);
    }

    @Override
    public boolean hasPermission(IPlayer player, String node, int vanillaLevel) {
        if (permissionsHandler == null) {
            debugLogger.debugLog("Forge 1.7.10 permission check before runtime binding: " + node);
            return false;
        }
        return permissionsHandler.hasPermission(player, node, vanillaLevel);
    }

    @Override
    public boolean hasVanillaPermissionLevel(IPlayer player, int level) {
        return level >= 0 && nativePlayer(player).canCommandSenderUseCommand(level, "paradigm");
    }

    @Override
    public void sendSystemMessage(IPlayer player, IComponent message) {
        nativePlayer(player).addChatMessage(nativeText(message));
    }

    @Override
    public void broadcastSystemMessage(IComponent message) {
        if (server != null)
            server.getConfigurationManager().sendChatMsg(nativeText(message));
    }

    @Override
    public void broadcastChatMessage(IComponent message) {
        broadcastSystemMessage(message);
    }

    @Override
    public void broadcastSystemMessage(
            IComponent message, String header, String footer, IPlayer player) {
        if (parser == null)
            throw new IllegalStateException("Message parser is not initialized");
        IComponent h = parser.parseMessage(header, player);
        IComponent f = parser.parseMessage(footer, player);
        for (IPlayer recipient : getOnlinePlayers()) {
            sendSystemMessage(recipient, h);
            sendSystemMessage(recipient, message);
            sendSystemMessage(recipient, f);
        }
    }

    @Override
    public void sendTitle(IPlayer player, IComponent title, IComponent subtitle) {
        throw unsupported("titles");
    }

    @Override
    public void sendSubtitle(IPlayer player, IComponent subtitle) {
        throw unsupported("subtitles");
    }

    @Override
    public void sendActionBar(IPlayer player, IComponent message) {
        throw unsupported("action bars");
    }

    @Override
    public void sendBossBar(List<IPlayer> players, IComponent message, int duration,
            BossBarColor color, float progress) {
        throw unsupported("boss bars");
    }

    @Override
    public void showPersistentBossBar(
            IPlayer player, IComponent message, BossBarColor color, BossBarOverlay overlay) {
        throw unsupported("boss bars");
    }

    @Override
    public void removePersistentBossBar(IPlayer player) {
        throw unsupported("boss bars");
    }

    @Override
    public void createOrUpdateRestartBossBar(
            IComponent message, BossBarColor color, float progress) {
        throw unsupported("boss bars");
    }

    @Override
    public void removeRestartBossBar() {
        throw unsupported("boss bars");
    }

    @Override
    public void clearTitles(IPlayer player) {
        throw unsupported("titles");
    }

    @Override
    public void playSound(
            IPlayer player, String sound, String category, float volume, float pitch) {
        throw unsupported("sound playback");
    }

    @Override
    public void executeCommandAs(ICommandSource source, String command) {
        if (server == null || !(source.getOriginalSource() instanceof ICommandSender))
            throw new IllegalStateException("No active command source");
        server.getCommandManager().executeCommand(
                (ICommandSender) source.getOriginalSource(), stripSlash(command));
    }

    @Override
    public void executeCommandAsConsole(String command) {
        if (server == null)
            throw new IllegalStateException("Server has not started");
        server.getCommandManager().executeCommand(server, stripSlash(command));
    }
    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    @Override
    public TaskScheduler getTaskScheduler() {
        return scheduler;
    }

    @Override
    public void executeOnServerThread(Runnable task) {
        if (task != null)
            mainTasks.add(task);
    }

    @Override
    public Object getConsoleCommandSource() {
        return server;
    }

    @Override
    public ICommandSource createCommandSourceForPlayer(IPlayer player) {
        return new MinecraftCommandSource(nativePlayer(player));
    }

    @Override
    public boolean setGameMode(IPlayer player, String mode) {
        throw unsupported("game mode changes");
    }

    @Override
    public boolean setMovementSpeed(IPlayer player, double speed) {
        throw unsupported("movement speed");
    }

    @Override
    public boolean setTimeOfDay(long time) {
        throw unsupported("world time");
    }

    @Override
    public boolean setWeather(String weather) {
        throw unsupported("weather");
    }

    @Override
    public boolean healPlayer(IPlayer player) {
        throw unsupported("healing");
    }

    @Override
    public boolean feedPlayer(IPlayer player) {
        throw unsupported("feeding");
    }

    @Override
    public Boolean toggleFlight(IPlayer player) {
        throw unsupported("flight");
    }

    @Override
    public boolean setPlayerSpeed(IPlayer player, float walk, float fly, double base) {
        throw unsupported("player speed");
    }

    @Override
    public boolean clearPlayerInventory(IPlayer player) {
        throw unsupported("inventory clearing");
    }

    @Override
    public boolean setPlayerInvulnerable(IPlayer player, boolean enabled) {
        throw unsupported("invulnerability");
    }

    @Override
    public boolean setPlayerVanished(IPlayer player, boolean enabled) {
        throw unsupported("vanish");
    }

    @Override
    public List<InventoryItem> inspectPlayerInventory(IPlayer player, boolean enderChest) {
        throw unsupported("inventory inspection");
    }

    @Override
    public int repairPlayerItems(IPlayer player, boolean all) {
        throw unsupported("item repair");
    }

    @Override
    public boolean enchantMainHand(IPlayer player, String enchantment, int level) {
        throw unsupported("enchantments");
    }

    @Override
    public Integer getHighestBlockY(IPlayer player) {
        throw unsupported("highest block lookup");
    }

    @Override
    public boolean jumpPlayerForward(IPlayer player, int distance) {
        throw unsupported("jump");
    }

    @Override
    public String replacePlaceholders(String text, IPlayer player) {
        if (placeholders == null)
            throw new IllegalStateException("Placeholders are not initialized");
        return placeholders.replacePlaceholders(text, player);
    }

    @Override
    public boolean hasPermissionForCustomCommand(ICommandSource source, CustomCommand command) {
        if (!command.isRequirePermission())
            return true;
        IPlayer player = source.getPlayer();
        return player == null ? source.isConsole() : hasPermission(player, command.getPermission());
    }

    @Override
    public void shutdownServer(IComponent message) {
        throw unsupported("server shutdown command");
    }

    @Override
    public void sendSuccess(ICommandSource source, IComponent message, boolean toOps) {
        if (toOps)
            throw unsupported("operator command notification");
        ((ICommandSender) source.getOriginalSource()).addChatMessage(nativeText(message));
    }

    @Override
    public void sendFailure(ICommandSource source, IComponent message) {
        sendSuccess(source, message, false);
    }

    @Override
    public void teleportPlayer(IPlayer player, double x, double y, double z) {
        throw unsupported("teleportation");
    }

    @Override
    public boolean playerHasItem(IPlayer player, String itemId, int amount) {
        throw unsupported("inventory matching");
    }

    @Override
    public boolean isPlayerInArea(
            IPlayer player, String worldId, List<Integer> a, List<Integer> b) {
        throw unsupported("area matching");
    }

    @Override
    public List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (IPlayer player : getOnlinePlayers()) names.add(player.getName());
        return names;
    }

    @Override
    public List<String> getWorldNames() {
        List<String> names = new ArrayList<>();
        if (server != null)
            for (net.minecraft.world.WorldServer world : server.worldServers)
                if (world != null)
                    names.add(String.valueOf(world.provider.dimensionId));
        return names;
    }

    @Override
    public int getPlayerPing(IPlayer player) {
        return nativePlayer(player).ping;
    }

    @Override
    public int getMaxPlayers() {
        return server == null ? 0 : server.getConfigurationManager().getMaxPlayers();
    }

    @Override
    public IPlayer wrapPlayer(Object player) {
        return new MinecraftPlayer((EntityPlayerMP) player);
    }

    @Override
    public ICommandSource wrapCommandSource(Object source) {
        return new MinecraftCommandSource((ICommandSender) source);
    }

    @Override
    public IComponent createEmptyComponent() {
        return new MinecraftComponent("");
    }

    @Override
    public IComponent parseFormattingCode(String code, IComponent current) {
        return current.withFormatting(code);
    }

    @Override
    public IComponent parseHexColor(String hex, IComponent current) {
        return current.withColorHex(hex);
    }

    @Override
    public IComponent wrap(Object text) {
        return new MinecraftComponent((IChatComponent) text);
    }

    @Override
    public IComponent createComponentFromLiteral(String text) {
        return createLiteralComponent(text);
    }

    @Override
    public String getMinecraftVersion() {
        return "1.7.10";
    }

    @Override
    public String getLoaderName() {
        return "forge";
    }

    @Override
    public String getPlayerRemoteAddress(IPlayer player) {
        EntityPlayerMP nativePlayer = nativePlayer(player);
        if (nativePlayer.playerNetServerHandler == null
                || nativePlayer.playerNetServerHandler.netManager == null) {
            return null;
        }
        SocketAddress address = nativePlayer.playerNetServerHandler.netManager.getSocketAddress();
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return null;
    }

    @Override
    public boolean disconnectPlayer(IPlayer player, IComponent reason) {
        EntityPlayerMP nativePlayer = nativePlayer(player);
        if (nativePlayer.playerNetServerHandler == null) {
            return false;
        }
        nativePlayer.playerNetServerHandler.kickPlayerFromServer(
                reason != null ? reason.getRawText() : "Disconnected");
        return true;
    }

    @Override
    public Object createStyleWithClickEvent(Object base, String action, String value) {
        MinecraftComponent component = new MinecraftComponent("");
        component.setStyle(base == null ? new ChatStyle() : base);
        IComponent updated = switch (action.toLowerCase(java.util.Locale.ROOT)) {
            case "run_command" -> component.onClickRunCommand(value);
            case "suggest_command" -> component.onClickSuggestCommand(value);
            case "open_url" -> component.onClickOpenUrl(value);
            default -> throw unsupported("click action " + action);
        };
        return updated.getStyle();
    }

    @Override
    public Object createStyleWithHoverEvent(Object base, Object hover) {
        MinecraftComponent component = new MinecraftComponent("");
        component.setStyle(base == null ? new ChatStyle() : base);
        IComponent text = hover instanceof IComponent wrapped
                ? wrapped
                : hover instanceof IChatComponent nativeText
                        ? wrap(nativeText)
                        : createLiteralComponent(String.valueOf(hover));
        IComponent updated = component.onHoverComponent(text);
        return updated.getStyle();
    }

    @Override
    public IConfig getConfig() {
        return config;
    }

    @Override
    public IEventSystem getEventSystem() {
        return events;
    }

    @Override
    public ICommandBuilder createCommandBuilder() {
        return new ForgeCommandBuilder();
    }

    @Override
    public void registerCommand(ICommandBuilder builder) {
        if (server == null || !(server.getCommandManager() instanceof CommandHandler manager)) {
            throw new IllegalStateException("Native command manager is not available");
        }
        if (!(builder.build() instanceof ForgeCommandBuilder root) || root.literalName() == null) {
            throw new IllegalArgumentException("Expected a Forge 1.7.10 literal command root");
        }
        String name = root.literalName();
        ICommand occupied = (ICommand) manager.getCommands().get(name);
        Object contributor = registeringContributor == null ? this : registeringContributor;
        if (occupied instanceof ForgeCommand own && own.ownedBy(this)) {
            commandCollisions.remove(name);
            own.addRoot(root, contributor);
            return;
        }
        if (occupied != null) {
            if (commandCollisions.add(name)) {
                LOGGER.warn("Paradigm command /{} was not registered: already owned by {}",
                        name, occupied.getClass().getName());
            }
            return;
        }
        ForgeCommand command = new ForgeCommand(root, this, contributor);
        manager.registerCommand(command);
        commandCollisions.remove(name);
        ownedCommands.add(command);
    }

    @Override
    public boolean ownsRegisteredCommandRoot(String rootLiteral) {
        if (server == null || !(server.getCommandManager() instanceof CommandHandler manager)) {
            return false;
        }
        ICommand command = (ICommand) manager.getCommands().get(rootLiteral);
        return command instanceof ForgeCommand own && own.ownedBy(this);
    }

    @Override
    public boolean unregisterCommandRoot(String rootLiteral) {
        if (server == null || !(server.getCommandManager() instanceof CommandHandler manager)) {
            return false;
        }
        ICommand occupied = (ICommand) manager.getCommands().get(rootLiteral);
        if (occupied instanceof ForgeCommand command && command.ownedBy(this)) {
            removeNativeCommand(manager, rootLiteral, command);
            ownedCommands.remove(command);
            return true;
        }
        for (ForgeCommand command : new ArrayList<>(ownedCommands)) {
            if (command.getCommandName().equals(rootLiteral)) {
                removeNativeCommand(manager, rootLiteral, command);
                ownedCommands.remove(command);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean refreshRegisteredCommandContributors() {
        if (server == null || !(server.getCommandManager() instanceof CommandHandler manager)) {
            return false;
        }
        for (ForgeCommand command : new ArrayList<>(ownedCommands)) {
            for (Object contributor : commandContributors.keySet()) {
                command.removeContributor(contributor);
            }
            if (command.isEmpty()) {
                removeNativeCommand(manager, command.getCommandName(), command);
                ownedCommands.remove(command);
            }
        }
        commandContributors.forEach(this::registerContributor);
        return true;
    }

    private static void removeNativeCommand(CommandHandler manager, String root, ForgeCommand command) {
        Set<?> commands = ReflectionHelper.getPrivateValue(CommandHandler.class, manager,
                "commandSet", "field_71561_b", "c");
        manager.getCommands().remove(root, command);
        commands.remove(command);
    }

    @Override
    public void rewireCommandTreePermissions() {
    }

    @Override
    public void refreshPlayerCommandTree(IPlayer player) {
    }

    @Override
    public void refreshAllPlayerCommandTrees() {
    }

    @Override
    public boolean isFirstJoin(IPlayer player) {
        throw unsupported("first-join detection");
    }
}
