package eu.avalanche7.paradigm.platform;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class MinecraftEventSystem implements IEventSystem {
    private static volatile MinecraftEventSystem active;
    private static final String JOINED = "paradigmJoined";
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
    }

    @SubscribeEvent
    public void joined(PlayerEvent.PlayerLoggedInEvent forge) {
        if (forge.player instanceof EntityPlayerMP player) {
            JoinLeaveEvent event = new JoinLeaveEvent(new MinecraftPlayer(player), pendingJoins.remove(player));
            for (PlayerJoinEventListener listener : joinListeners) {
                listener.onPlayerJoin(event);
            }
            markJoined(player);
            broadcast(event.message);
        }
    }

    @SubscribeEvent
    public void left(PlayerEvent.PlayerLoggedOutEvent forge) {
        if (forge.player instanceof EntityPlayerMP player) {
            JoinLeaveEvent event = new JoinLeaveEvent(new MinecraftPlayer(player), pendingLeaves.remove(player));
            for (PlayerLeaveEventListener listener : leaveListeners) {
                listener.onPlayerLeave(event);
            }
            broadcast(event.message);
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

    private static void broadcast(IComponent message) {
        MinecraftServer server = MinecraftServer.getServer();
        if (message != null && server != null) {
            server.getConfigurationManager().sendChatMsg((IChatComponent) message.getOriginalText());
        }
    }

    static boolean firstJoin(EntityPlayerMP player) {
        return !player.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).getBoolean(JOINED)
                && player.func_147099_x().writeStat(StatList.leaveGameStat) == 0;
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

        private JoinLeaveEvent(IPlayer player, IChatComponent message) {
            this.player = player;
            this.message = message != null ? new MinecraftComponent(message) : null;
        }

        @Override
        public IPlayer getPlayer() {
            return player;
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
