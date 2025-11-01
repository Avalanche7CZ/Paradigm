package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.bus.api.SubscribeEvent;

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

    public static final class DispatchResult {
        public final String message;
        public final boolean cancelled;
        public DispatchResult(String message, boolean cancelled) {
            this.message = message;
            this.cancelled = cancelled;
        }
    }

    public DispatchResult dispatchChatRaw(ServerPlayer player, String raw) {
        if (chatListeners.isEmpty()) return new DispatchResult(raw, false);
        RawChatEvent evt = new RawChatEvent(player, raw);
        for (ChatEventListener l : chatListeners) {
            try {
                l.onPlayerChat(evt);
            } catch (Exception e) {
                System.err.println("Error in chat event listener: " + e.getMessage());
            }
        }
        return new DispatchResult(evt.msg, evt.cancelled);
    }

    private static class RawChatEvent implements ChatEvent {
        private final IPlayer player;
        private String msg;
        private boolean cancelled;
        RawChatEvent(ServerPlayer sp, String raw) {
            this.player = MinecraftPlayer.of(sp);
            this.msg = raw;
        }
        @Override public IPlayer getPlayer() { return player; }
        @Override public String getMessage() { return msg; }
        @Override public void setMessage(String message) { if (message != null) this.msg = message; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    }

    public java.util.List<ChatEventListener> getChatListenersInternal() {
        return chatListeners;
    }

    public void dispatchJoin(PlayerEvent.PlayerLoggedInEvent event) {
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

    public void dispatchLeave(PlayerEvent.PlayerLoggedOutEvent event) {
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

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        dispatchJoin(event);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        dispatchLeave(event);
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer sp = event.getPlayer();
        String raw = event.getMessage().getString();
        DispatchResult result = dispatchChatRaw(sp, raw);
        if (result.cancelled || !raw.equals(result.message)) {
            event.setCanceled(true);
        }
    }

    private static class MinecraftPlayerJoinEvent implements PlayerJoinEvent {
        private final IPlayer player;
        public MinecraftPlayerJoinEvent(PlayerEvent.PlayerLoggedInEvent neoforgeEvent) {
            this.player = MinecraftPlayer.of((ServerPlayer) neoforgeEvent.getEntity());
        }
        @Override public IPlayer getPlayer() { return player; }
        @Override public IComponent getJoinMessage() { return null; }
        @Override public void setJoinMessage(IComponent message) {}
    }

    private static class MinecraftPlayerLeaveEvent implements PlayerLeaveEvent {
        private final IPlayer player;
        public MinecraftPlayerLeaveEvent(PlayerEvent.PlayerLoggedOutEvent neoforgeEvent) {
            this.player = MinecraftPlayer.of((ServerPlayer) neoforgeEvent.getEntity());
        }
        @Override public IPlayer getPlayer() { return player; }
        @Override public IComponent getLeaveMessage() { return null; }
        @Override public void setLeaveMessage(IComponent message) {}
    }
}
