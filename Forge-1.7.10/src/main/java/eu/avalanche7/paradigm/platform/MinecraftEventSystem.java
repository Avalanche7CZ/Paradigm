package eu.avalanche7.paradigm.platform;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.stats.StatList;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.world.WorldEvent;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.AutomaticSpawnPlacement;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.visual.LegacyVisualController;

public final class MinecraftEventSystem implements IEventSystem {
    private final PlatformAdapterImpl platform;

    public MinecraftEventSystem() {
        this(null);
    }

    MinecraftEventSystem(PlatformAdapterImpl platform) {
        this.platform = platform;
    }

    private static volatile MinecraftEventSystem active;
    private static final String JOINED = "paradigmJoined";
    private final Map<EntityPlayerMP, Boolean> existingPlayerData = new WeakHashMap<>();
    private final Map<EntityPlayerMP, JoinPlacement> nativeJoinPlacements = new WeakHashMap<>();
    private final Set<EntityPlayerMP> customJoinPlacements = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<EntityPlayerMP, IChatComponent> pendingJoins = new IdentityHashMap<>();
    private final Map<EntityPlayerMP, IChatComponent> pendingLeaves = new IdentityHashMap<>();
    private final List<ChatEventListener> chatListeners = new CopyOnWriteArrayList<>();
    private final List<PlayerJoinEventListener> joinListeners = new CopyOnWriteArrayList<>();
    private final List<PlayerLeaveEventListener> leaveListeners = new CopyOnWriteArrayList<>();
    private final List<PlayerDeathEventListener> deathListeners = new CopyOnWriteArrayList<>();
    private final List<PlayerCommandEventListener> commandListeners = new CopyOnWriteArrayList<>();

    public void register() {
        active = this;
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    public void unregister() {
        if (active == this) active = null;
        existingPlayerData.clear();
        nativeJoinPlacements.clear();
        customJoinPlacements.clear();
        pendingJoins.clear();
        pendingLeaves.clear();
        MinecraftForge.EVENT_BUS.unregister(this);
        FMLCommonHandler.instance().bus().unregister(this);
        chatListeners.clear();
        joinListeners.clear();
        leaveListeners.clear();
        deathListeners.clear();
        commandListeners.clear();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void entityJoined(EntityJoinWorldEvent event) {
        LegacyVisualController visuals = LegacyVisualController.current();
        if (visuals != null && !event.world.isRemote) visuals.bossbars().entityJoined(event.entity);
    }

    @SubscribeEvent
    public void worldUnloaded(WorldEvent.Unload event) {
        LegacyVisualController visuals = LegacyVisualController.current();
        if (visuals != null && !event.world.isRemote) visuals.bossbars().worldUnloaded(event.world);
    }

    @Override
    public void onPlayerChat(ChatEventListener listener) {
        chatListeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void onPlayerJoin(PlayerJoinEventListener listener) {
        joinListeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void onPlayerLeave(PlayerLeaveEventListener listener) {
        leaveListeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void onPlayerDeath(PlayerDeathEventListener listener) {
        deathListeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void onPlayerCommand(PlayerCommandEventListener listener) {
        commandListeners.add(Objects.requireNonNull(listener));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void chat(ServerChatEvent forge) {
        if (platform != null && !platform.isServerThread()) {
            try {
                CrucibleChatBridge.onServerThread(platform, () -> { handleChat(forge); return null; });
            } catch (RuntimeException failure) {
                forge.setCanceled(true);
                org.slf4j.LoggerFactory.getLogger(MinecraftEventSystem.class).error("Forge chat handling failed; message cancelled", failure);
            }
            return;
        }
        handleChat(forge);
    }

    private void handleChat(ServerChatEvent forge) {
        ChatEvent event = new ChatEvent() {
            private String message = forge.message;

            @Override
            public IPlayer getPlayer() {
                return new MinecraftPlayer(forge.player);
            }

            @Override
            public String getMessage() {
                return message;
            }

            @Override
            public void setMessage(String value) {
                message = Objects.requireNonNull(
                        value, "Chat message cannot be null; cancel the event instead");
                forge.component =
                        new ChatComponentTranslation("chat.type.text", forge.username, value);
            }

            @Override
            public boolean isCancelled() {
                return forge.isCanceled();
            }

            @Override
            public void setCancelled(boolean cancelled) {
                forge.setCanceled(cancelled);
            }
        };
        for (ChatEventListener listener : chatListeners) {
            listener.onPlayerChat(event);
            if (event.isCancelled()) {
                break;
            }
        }
        if (event.isCancelled()) return;

        Services services = Paradigm.getServices();
        if (services == null || CrucibleChatBridge.available()) return;
        IComponent formatted = services.getChatFormatter().format(event.getPlayer(), event.getMessage());
        if (formatted != null) {
            forge.component = new ChatComponentTranslation("%s", formatted.getOriginalText());
        }
    }

    public static void captureLoadedPlayerData(EntityPlayerMP player, boolean exists) {
        MinecraftEventSystem system = active;
        if (system != null) {
            system.existingPlayerData.merge(player, exists, (before, loaded) -> before || loaded);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void playerDataLoaded(net.minecraftforge.event.entity.player.PlayerEvent.LoadFromFile event) {
        if (event.entityPlayer instanceof EntityPlayerMP player && event.playerDirectory != null
                && player.getUniqueID().toString().equals(event.playerUUID)) {
            existingPlayerData.merge(player,
                    !AutomaticSpawnPlacement.noSavedPlayerData(event.playerDirectory.toPath(), event.playerUUID),
                    (before, exists) -> before || exists);
        }
    }

    public static void captureJoinPlacement(EntityPlayerMP player) {
        MinecraftEventSystem system = active;
        if (system != null) system.nativeJoinPlacements.put(player, JoinPlacement.capture(player));
    }

    public static void checkJoinPlacement(EntityPlayerMP player) {
        MinecraftEventSystem system = active;
        if (system == null) return;
        JoinPlacement before = system.nativeJoinPlacements.remove(player);
        if (before != null && !before.equals(JoinPlacement.capture(player))) system.customJoinPlacements.add(player);
    }

    private record JoinPlacement(Object world, double x, double y, double z, float yaw, float pitch, int teleportSequence) {
        static JoinPlacement capture(EntityPlayerMP player) {
            return new JoinPlacement(player.worldObj, player.posX, player.posY, player.posZ,
                    player.rotationYaw, player.rotationPitch, new MinecraftPlayer(player).getTeleportSequence());
        }
    }

    @SubscribeEvent
    public void joined(PlayerEvent.PlayerLoggedInEvent forge) {
        if (forge.player instanceof EntityPlayerMP player) {
            boolean announce = pendingJoins.containsKey(player) && visible(player);
            Boolean existingData = existingPlayerData.remove(player);
            FirstJoinStatus status = classifyJoin(existingData,
                    Boolean.FALSE.equals(existingData) ? noJoinHistory(player) : null);
            JoinLeaveEvent event = new JoinLeaveEvent(new MinecraftPlayer(player), pendingJoins.remove(player),
                    status, customJoinPlacements.remove(player));
            for (PlayerJoinEventListener listener : joinListeners) {
                listener.onPlayerJoin(event);
            }
            markJoined(player);
            if (announce) broadcast(event.message);
        }
    }

    @SubscribeEvent
    public void left(PlayerEvent.PlayerLoggedOutEvent forge) {
        if (forge.player instanceof EntityPlayerMP player) {
            boolean announce = pendingLeaves.containsKey(player) && visible(player);
            JoinLeaveEvent event = new JoinLeaveEvent(new MinecraftPlayer(player), pendingLeaves.remove(player), FirstJoinStatus.RETURNING, false);
            for (PlayerLeaveEventListener listener : leaveListeners) {
                listener.onPlayerLeave(event);
            }
            if (announce) broadcast(event.message);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void died(LivingDeathEvent forge) {
        if (forge.entityLiving instanceof EntityPlayerMP player) {
            IPlayer wrapped = new MinecraftPlayer(player);
            for (PlayerDeathEventListener listener : deathListeners) {
                listener.onPlayerDeath(() -> wrapped);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void command(CommandEvent forge) {
        if (platform != null) {
            platform.guardHybridCommandOwnership(forge);
            if (forge.isCanceled()) return;
        }
        if (!(forge.sender instanceof EntityPlayerMP player)) {
            return;
        }
        PlayerCommandEvent event = new PlayerCommandEvent() {
            @Override
            public IPlayer getPlayer() {
                return new MinecraftPlayer(player);
            }

            @Override
            public String getCommand() {
                String root = forge.command.getCommandName();
                return forge.parameters.length == 0 ? root : root + " " + String.join(" ", forge.parameters);
            }

            @Override
            public boolean isCancelled() {
                return forge.isCanceled();
            }

            @Override
            public void setCancelled(boolean cancelled) {
                forge.setCanceled(cancelled);
            }
        };
        for (PlayerCommandEventListener listener : commandListeners) {
            listener.onPlayerCommand(event);
            if (event.isCancelled()) {
                break;
            }
        }
    }

    public static void captureJoin(ServerConfigurationManager manager, EntityPlayerMP player, IChatComponent message) {
        MinecraftEventSystem system = active;
        if (system == null) manager.sendChatMsg(message);
        else system.pendingJoins.put(player, message);
    }

    public static void captureLeave(ServerConfigurationManager manager, EntityPlayerMP player, IChatComponent message) {
        MinecraftEventSystem system = active;
        if (system == null) manager.sendChatMsg(message);
        else system.pendingLeaves.put(player, message);
    }

    private static boolean visible(EntityPlayerMP player) {
        var visuals = eu.avalanche7.paradigm.platform.visual.LegacyVisualController.current();
        return visuals == null || visuals.vanish().visible(player, null);
    }

    private static void broadcast(IComponent message) {
        MinecraftServer server = MinecraftServer.getServer();
        if (message != null && server != null) {
            server.getConfigurationManager().sendChatMsg((IChatComponent) message.getOriginalText());
        }
    }

    static FirstJoinStatus classifyJoin(Boolean existingData, Boolean noJoinHistory) {
        if (Boolean.TRUE.equals(existingData) || Boolean.FALSE.equals(noJoinHistory)) return FirstJoinStatus.RETURNING;
        return existingData == null || noJoinHistory == null ? FirstJoinStatus.UNAVAILABLE : FirstJoinStatus.FIRST_JOIN;
    }

    static boolean firstJoin(EntityPlayerMP player) {
        return Boolean.TRUE.equals(noJoinHistory(player));
    }

    private static Boolean noJoinHistory(EntityPlayerMP player) {
        if (player.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).getBoolean(JOINED)) return false;
        var statistics = player.func_147099_x();
        return statistics != null ? statistics.writeStat(StatList.leaveGameStat) == 0 : null;
    }

    private static void markJoined(EntityPlayerMP player) {
        NBTTagCompound data = player.getEntityData();
        NBTTagCompound persisted = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        persisted.setBoolean(JOINED, true);
        data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
    }

    private static final class JoinLeaveEvent implements PlayerJoinEvent, PlayerLeaveEvent {
        private final IPlayer player;
        private IComponent message;
        private final FirstJoinStatus firstJoinStatus;
        private final boolean customJoinPlacement;

        private JoinLeaveEvent(IPlayer player, IChatComponent message, FirstJoinStatus firstJoinStatus, boolean customJoinPlacement) {
            this.player = player;
            this.firstJoinStatus = firstJoinStatus;
            this.customJoinPlacement = customJoinPlacement;
            this.message = message != null ? new MinecraftComponent(message) : null;
        }

        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public boolean isFirstJoin(eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter platform) {
            return firstJoinStatus == FirstJoinStatus.FIRST_JOIN;
        }

        @Override
        public FirstJoinStatus firstJoinStatus(eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter platform) {
            return firstJoinStatus;
        }

        @Override
        public boolean hasCustomJoinPlacement() {
            return customJoinPlacement;
        }

        @Override
        public IComponent getJoinMessage() {
            return message;
        }

        @Override
        public void setJoinMessage(IComponent value) {
            message = value;
        }

        @Override
        public IComponent getLeaveMessage() {
            return message;
        }

        @Override
        public void setLeaveMessage(IComponent value) {
            message = value;
        }
    }
}
