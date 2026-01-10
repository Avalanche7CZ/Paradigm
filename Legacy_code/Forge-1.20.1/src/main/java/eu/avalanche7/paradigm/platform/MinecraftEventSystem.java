package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.concurrent.CopyOnWriteArrayList;

public class MinecraftEventSystem implements IEventSystem {

    private final CopyOnWriteArrayList<ChatEventListener> chatListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerJoinEventListener> joinListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerLeaveEventListener> leaveListeners = new CopyOnWriteArrayList<>();

    @Override
    public void registerChatListener(ChatEventListener listener) {
        chatListeners.add(listener);
    }

    @Override
    public void registerJoinListener(PlayerJoinEventListener listener) {
        joinListeners.add(listener);
    }

    @Override
    public void registerLeaveListener(PlayerLeaveEventListener listener) {
        leaveListeners.add(listener);
    }

    @Override
    public void unregisterChatListener(ChatEventListener listener) {
        chatListeners.remove(listener);
    }

    @Override
    public void unregisterJoinListener(PlayerJoinEventListener listener) {
        joinListeners.remove(listener);
    }

    @Override
    public void unregisterLeaveListener(PlayerLeaveEventListener listener) {
        leaveListeners.remove(listener);
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (chatListeners.isEmpty()) return;

        MinecraftChatEvent chatEvent = new MinecraftChatEvent(event);
        for (ChatEventListener listener : chatListeners) {
            try {
                listener.onPlayerChat(chatEvent);
            } catch (Exception e) {
                System.err.println("Error in chat event listener: " + e.getMessage());
            }
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (joinListeners.isEmpty()) return;

        MinecraftPlayerJoinEvent joinEvent = new MinecraftPlayerJoinEvent(event);
        for (PlayerJoinEventListener listener : joinListeners) {
            try {
                listener.onPlayerJoin(joinEvent);
            } catch (Exception e) {
                System.err.println("Error in player join event listener: " + e.getMessage());
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (leaveListeners.isEmpty()) return;

        MinecraftPlayerLeaveEvent leaveEvent = new MinecraftPlayerLeaveEvent(event);
        for (PlayerLeaveEventListener listener : leaveListeners) {
            try {
                listener.onPlayerLeave(leaveEvent);
            } catch (Exception e) {
                System.err.println("Error in player leave event listener: " + e.getMessage());
            }
        }
    }

    private static class MinecraftChatEvent implements ChatEvent {
        private final ServerChatEvent forgeEvent;
        private final IPlayer player;

        public MinecraftChatEvent(ServerChatEvent forgeEvent) {
            this.forgeEvent = forgeEvent;
            this.player = MinecraftPlayer.of((ServerPlayer) forgeEvent.getPlayer());
        }

        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public String getMessage() {
            return forgeEvent.getMessage().getString();
        }

        @Override
        public void setMessage(String message) {
            if (message == null || message.equals(getMessage())) {
                return;
            }
            forgeEvent.setCanceled(true);
        }

        @Override
        public boolean isCancelled() {
            return forgeEvent.isCanceled();
        }

        @Override
        public void setCancelled(boolean cancelled) {
            forgeEvent.setCanceled(cancelled);
        }
    }

    private static class MinecraftPlayerJoinEvent implements PlayerJoinEvent {
        private final PlayerEvent.PlayerLoggedInEvent forgeEvent;
        private final IPlayer player;

        public MinecraftPlayerJoinEvent(PlayerEvent.PlayerLoggedInEvent forgeEvent) {
            this.forgeEvent = forgeEvent;
            this.player = MinecraftPlayer.of((ServerPlayer) forgeEvent.getEntity());
        }

        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public IComponent getJoinMessage() {
            return null;
        }

        @Override
        public void setJoinMessage(IComponent message) {
        }
    }

    private static class MinecraftPlayerLeaveEvent implements PlayerLeaveEvent {
        private final PlayerEvent.PlayerLoggedOutEvent forgeEvent;
        private final IPlayer player;

        public MinecraftPlayerLeaveEvent(PlayerEvent.PlayerLoggedOutEvent forgeEvent) {
            this.forgeEvent = forgeEvent;
            this.player = MinecraftPlayer.of((ServerPlayer) forgeEvent.getEntity());
        }

        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public IComponent getLeaveMessage() {
            return null;
        }

        @Override
        public void setLeaveMessage(IComponent message) {
        }
    }
}
