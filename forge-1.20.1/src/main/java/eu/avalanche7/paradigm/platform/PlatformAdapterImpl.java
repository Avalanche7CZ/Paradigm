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
import net.minecraft.resources.ResourceLocation;
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

public class PlatformAdapterImpl implements IPlatformAdapter {

    private MinecraftServer server;
    private MessageParser messageParser;
    private final PermissionsHandler permissionsHandler;
    private final Placeholders placeholders;
    private final TaskScheduler taskScheduler;
    private final DebugLogger debugLogger;
    private final Map<UUID, ServerBossEvent> persistentBossBars = new HashMap<>();
    private ServerBossEvent restartBossBar;
    private final MinecraftEventSystem eventSystem;

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
        this.eventSystem = new MinecraftEventSystem();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this.eventSystem);
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
    public IPlayer getPlayerByUuid(UUID uuid) {
        ServerPlayer player = ((MinecraftServer) getMinecraftServer()).getPlayerList().getPlayer(uuid);
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
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(itemId));
        return item != null ? new ItemStack(item) : new ItemStack(Items.STONE);
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode) {
        return permissionsHandler.hasPermission(((MinecraftPlayer) player).getHandle(), permissionNode);
    }

    @Override
    public boolean hasPermission(IPlayer player, String permissionNode, int vanillaLevel) {
        ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
        return permissionsHandler.hasPermission(mcPlayer, permissionNode) || mcPlayer.hasPermissions(vanillaLevel);
    }

    @Override
    public boolean hasPermissionNode(IPlayer player, String permission) {
        return hasPermission(player, permission);
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

    @Override
    public void sendSystemMessage(IPlayer player, String message) {
        if (messageParser != null) {
            IComponent parsed = messageParser.parseMessage(message, player);
            sendSystemMessage(player, parsed);
        }
    }

    @Override
    public void sendActionBar(IPlayer player, String message) {
        if (messageParser != null) {
            ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
            IComponent parsed = messageParser.parseMessage(message, player);
            sendActionBar(player, parsed);
        }
    }

    @Override
    public void sendBossBar(List<IPlayer> players, String message, int durationSeconds, BossBarColor color, float progress) {
        if (messageParser != null) {
            IComponent parsed = messageParser.parseMessage(message, !players.isEmpty() ? players.get(0) : null);
            sendBossBar(players, parsed, durationSeconds, color, progress);
        }
    }

    private BossEvent.BossBarColor toMinecraftColor(BossBarColor color) {
        return BossEvent.BossBarColor.valueOf(color.name());
    }

    private BossEvent.BossBarOverlay toMinecraftOverlay(BossBarOverlay overlay) {
        return BossEvent.BossBarOverlay.valueOf(overlay.name());
    }

    @Override
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

    @Override
    public void showPersistentBossBar(IPlayer player, IComponent message, BossBarColor color, BossBarOverlay overlay) {
        removePersistentBossBar(player);
        Component mcMessage = ((MinecraftComponent) message).getHandle();
        ServerBossEvent bossEvent = new ServerBossEvent(mcMessage, toMinecraftColor(color), toMinecraftOverlay(overlay));
        bossEvent.addPlayer(((MinecraftPlayer) player).getHandle());
        persistentBossBars.put(UUID.fromString(player.getUUID()), bossEvent);
    }

    @Override
    public void removePersistentBossBar(IPlayer player) {
        ServerBossEvent bossBar = persistentBossBars.remove(UUID.fromString(player.getUUID()));
        if (bossBar != null) {
            bossBar.removePlayer(((MinecraftPlayer) player).getHandle());
        }
    }

    @Override
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

    @Override
    public void createOrUpdateRestartBossBar(String message, BossBarColor color, float progress) {
        if (messageParser != null) {
            IComponent parsed = messageParser.parseMessage(message, null);
            createOrUpdateRestartBossBar(parsed, color, progress);
        }
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
                debugLogger.debugLog("PlatformAdapterImpl: Exception during shutdown: {}", e.getMessage());
            }
        }
    }

    @Override
    public void shutdownServer(String kickMessage) {
        MinecraftServer server = (MinecraftServer) getMinecraftServer();
        if (server != null) {
            try {
                debugLogger.debugLog("PlatformAdapterImpl: shutdownServer called with message: {}", kickMessage);
                server.getPlayerList().broadcastSystemMessage(Component.literal(kickMessage), false);
                server.saveEverything(true, true, true);
                debugLogger.debugLog("PlatformAdapterImpl: Calling server.halt(false)");
                server.halt(false);
                debugLogger.debugLog("PlatformAdapterImpl: server.halt(false) called");
            } catch (Exception e) {
                e.printStackTrace();
                debugLogger.debugLog("PlatformAdapterImpl: Exception during shutdown: {}", e.getMessage());
            }
        } else {
            debugLogger.debugLog("PlatformAdapterImpl: shutdownServer called but server is null!");
        }
    }

    @Override
    public void sendSuccess(Object source, IComponent message, boolean toOps) {
        if (source instanceof CommandSourceStack stack && message instanceof MinecraftComponent mc) {
            stack.sendSuccess(() -> mc.getHandle(), toOps);
        }
    }

    @Override
    public void sendFailure(Object source, IComponent message) {
        if (source instanceof CommandSourceStack stack && message instanceof MinecraftComponent mc) {
            stack.sendFailure(mc.getHandle());
        }
    }

    @Override
    public boolean hasCommandPermission(Object source, String permission) {
        if (!(source instanceof CommandSourceStack stack)) return true;
        if (!(stack.getEntity() instanceof ServerPlayer player)) return true;
        return permissionsHandler.hasPermission(player, permission);
    }

    @Override
    public boolean hasCommandPermission(Object source, String permission, int vanillaLevel) {
        if (!(source instanceof CommandSourceStack stack)) return true;
        if (!(stack.getEntity() instanceof ServerPlayer player)) return true;
        return permissionsHandler.hasPermission(player, permission) || player.hasPermissions(vanillaLevel);
    }

    @Override
    public void executeCommandAs(Object source, String command) {
        if (server == null || command == null || source == null) return;
        if (source instanceof CommandSourceStack stack) {
            var dispatcher = server.getCommands().getDispatcher();
            var parseResults = dispatcher.parse(command, stack);
            try {
                dispatcher.execute(parseResults);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                debugLogger.debugLog("Command execution failed: " + command, e);
            }
        }
    }

    @Override
    public void executeCommandAsConsole(String command) {
        if (server == null || command == null) return;
        CommandSourceStack console = server.createCommandSourceStack();
        var dispatcher = server.getCommands().getDispatcher();
        var parseResults = dispatcher.parse(command, console);
        try {
            dispatcher.execute(parseResults);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            debugLogger.debugLog("Console command execution failed: " + command, e);
        }
    }

    @Override
    public void teleportPlayer(IPlayer player, double x, double y, double z) {
        if (player instanceof MinecraftPlayer mcPlayer) {
            mcPlayer.getHandle().teleportTo(x, y, z);
        }
    }

    @Override
    public boolean playerHasItem(IPlayer player, String itemId, int amount) {
        ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
        if (mcPlayer == null || itemId == null) {
            return false;
        }
        try {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(itemId));
            if (item == null || item == Items.AIR) {
                debugLogger.debugLog("PlatformAdapter: Could not find item with ID: " + itemId);
                return false;
            }
            return mcPlayer.getInventory().countItem(item) >= amount;
        } catch (Exception e) {
            debugLogger.debugLog("Error checking player items: " + itemId, e);
            return false;
        }
    }

    @Override
    public boolean isPlayerInArea(IPlayer player, String worldId, List<Integer> corner1, List<Integer> corner2) {
        ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
        if (mcPlayer == null || worldId == null || corner1 == null || corner2 == null || corner1.size() != 3 || corner2.size() != 3) {
            return false;
        }

        try {
            ResourceKey<Level> targetWorldKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(worldId));
            if (!mcPlayer.level().dimension().equals(targetWorldKey)) {
                return false;
            }

            Vec3 pos = mcPlayer.position();
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
            debugLogger.debugLog("Error checking player area: " + worldId, e);
            return false;
        }
    }

    @Override
    public boolean isModLoaded(String modId) {
        try {
            return net.minecraftforge.fml.ModList.get().isLoaded(modId);
        } catch (Exception e) {
            debugLogger.debugLog("Error checking mod loaded: " + modId, e);
            return false;
        }
    }

    @Override
    public void playSound(IPlayer player, String soundId, SoundCategory category, float volume, float pitch) {
        if (!(player instanceof MinecraftPlayer)) return;
        ServerPlayer mcPlayer = ((MinecraftPlayer) player).getHandle();
        try {
            SoundEvent soundEvent = ForgeRegistries.SOUND_EVENTS.getValue(ResourceLocation.parse(soundId));
            if (soundEvent != null) {
                SoundSource mcCategory = SoundSource.valueOf(category.name());
                mcPlayer.playNotifySound(soundEvent, mcCategory, volume, pitch);
            }
        } catch (Exception e) {
            debugLogger.debugLog("Failed to play sound: " + soundId, e);
        }
    }

    @Override
    public ICommandSource wrapCommandSource(Object source) {
        if (source instanceof CommandSourceStack stack) {
            return new MinecraftCommandSource(stack);
        }
        throw new IllegalArgumentException("Unsupported command source type: " + source.getClass());
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

    @Override
    public boolean hasCommandPermission(ICommandSource source, String permission) {
        IPlayer player = source.getPlayer();
        if (player != null) {
            return hasPermission(player, permission);
        }
        return true;
    }

    @Override
    public boolean hasCommandPermission(ICommandSource source, String permission, int vanillaLevel) {
        IPlayer player = source.getPlayer();
        if (player != null) {
            return hasPermission(player, permission, vanillaLevel);
        }
        return true;
    }

    public String replacePlaceholders(String text, @Nullable IPlayer player) {
        return placeholders.replacePlaceholders(text, player);
    }

    @Override
    public boolean hasPermissionForCustomCommand(Object source, CustomCommand command) {
        if (!(source instanceof CommandSourceStack)) return true;
        CommandSourceStack stack = (CommandSourceStack) source;
        if (!command.isRequirePermission()) {
            return true;
        }
        if (!(stack.getEntity() instanceof ServerPlayer player)) {
            return true;
        }
        return permissionsHandler.hasPermission(player, command.getPermission());
    }

    @Override
    public void executeCommandAs(ICommandSource source, String command) {
        if (source instanceof MinecraftCommandSource mcSource) {
            executeCommandAs(mcSource.getHandle(), command);
        }
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
    public Integer parseColorToRgb(String colorName) {
        if (colorName == null || colorName.isEmpty()) {
            return null;
        }

        if (colorName.startsWith("#")) {
            try {
                return Integer.parseInt(colorName.substring(1), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        for (net.minecraft.ChatFormatting format : net.minecraft.ChatFormatting.values()) {
            if (format.isColor() && format.getName() != null && format.getName().equalsIgnoreCase(colorName)) {
                return format.getColor();
            }
        }

        return null;
    }

    @Override
    public String getColorNameFromFormatting(Object formatting) {
        if (formatting instanceof net.minecraft.ChatFormatting chatFormatting) {
            return chatFormatting.getName();
        }
        return null;
    }

    @Override
    public boolean isValidColorName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        for (net.minecraft.ChatFormatting format : net.minecraft.ChatFormatting.values()) {
            if (format.isColor() && format.getName() != null && format.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }

        return false;
    }
}
