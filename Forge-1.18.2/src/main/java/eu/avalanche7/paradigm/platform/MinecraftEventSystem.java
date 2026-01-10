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
    public void onPlayerChat(ChatEventListener listener) {
        chatListeners.add(listener);
    }

    @Override
    public void onPlayerJoin(PlayerJoinEventListener listener) {
        joinListeners.add(listener);
    }

    @Override
    public void onPlayerLeave(PlayerLeaveEventListener listener) {
        leaveListeners.add(listener);
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (chatListeners.isEmpty()) return;

        MinecraftChatEvent chatEvent = new MinecraftChatEvent(event);
        for (ChatEventListener listener : chatListeners) {
            try {
                listener.onPlayerChat(chatEvent);
            } catch (Exception ignored) {
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
            } catch (Exception ignored) {
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
            } catch (Exception ignored) {
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
            try {
                Object m = forgeEvent.getMessage();
                return m != null ? String.valueOf(m) : "";
            } catch (Throwable t) {
                return "";
            }
        }

        @Override
        public void setMessage(String message) {
            // Not safely supported in this version; cancel to prevent duplicates.
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
