package eu.avalanche7.paradigm.platform;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class MinecraftEventSystem implements IEventSystem {
    private final List<ChatEventListener> chatListeners = new CopyOnWriteArrayList<>();
    private final List<PlayerJoinEventListener> joinListeners = new CopyOnWriteArrayList<>();
    private final List<PlayerLeaveEventListener> leaveListeners = new CopyOnWriteArrayList<>();
    private final List<PlayerDeathEventListener> deathListeners = new CopyOnWriteArrayList<>();
    private final List<PlayerCommandEventListener> commandListeners = new CopyOnWriteArrayList<>();

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    public void unregister() {
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
            PlayerJoinEvent event = new JoinLeaveEvent(new MinecraftPlayer(player));
            for (PlayerJoinEventListener listener : joinListeners) {
                listener.onPlayerJoin(event);
            }
        }
    }

    @SubscribeEvent
    public void left(PlayerEvent.PlayerLoggedOutEvent forge) {
        if (forge.player instanceof EntityPlayerMP player) {
            PlayerLeaveEvent event = new JoinLeaveEvent(new MinecraftPlayer(player));
            for (PlayerLeaveEventListener listener : leaveListeners) {
                listener.onPlayerLeave(event);
            }
        }
    }

    @SubscribeEvent
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

    private record JoinLeaveEvent(IPlayer player) implements PlayerJoinEvent, PlayerLeaveEvent {
        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public IComponent getJoinMessage() {
            return null;
        }

        @Override
        public void setJoinMessage(IComponent value) {
            throw new UnsupportedOperationException(
                    "Forge 1.7.10 login events cannot replace the vanilla join announcement");
        }

        @Override
        public IComponent getLeaveMessage() {
            return null;
        }

        @Override
        public void setLeaveMessage(IComponent value) {
            throw new UnsupportedOperationException(
                    "Forge 1.7.10 logout events cannot replace the vanilla leave announcement");
        }
    }
}
