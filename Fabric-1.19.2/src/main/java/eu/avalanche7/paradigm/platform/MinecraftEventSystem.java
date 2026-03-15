package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import java.util.concurrent.CopyOnWriteArrayList;

public class MinecraftEventSystem implements IEventSystem {
    private final CopyOnWriteArrayList<PlayerJoinEventListener> joinListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerLeaveEventListener> leaveListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<ChatEventListener> chatListeners = new CopyOnWriteArrayList<>();

    public MinecraftEventSystem() {
        // Chat event registration will be handled via mixin !!!! - By Avalanche7 14.09.2025 - For Now
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (joinListeners.isEmpty()) return;
            MinecraftPlayerJoinEvent joinEvent = new MinecraftPlayerJoinEvent(player);
            for (PlayerJoinEventListener listener : joinListeners) {
                try {
                    listener.onPlayerJoin(joinEvent);
                } catch (Exception e) {
                    System.err.println("Error in player join event listener: " + e.getMessage());
                }
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (leaveListeners.isEmpty()) return;
            MinecraftPlayerLeaveEvent leaveEvent = new MinecraftPlayerLeaveEvent(player);
            for (PlayerLeaveEventListener listener : leaveListeners) {
                try {
                    listener.onPlayerLeave(leaveEvent);
                } catch (Exception e) {
                    System.err.println("Error in player leave event listener: " + e.getMessage());
                }
            }
        });
    }

    public void registerJoinListener(PlayerJoinEventListener listener) {
        joinListeners.add(listener);
    }

    public void registerLeaveListener(PlayerLeaveEventListener listener) {
        leaveListeners.add(listener);
    }

    public void unregisterJoinListener(PlayerJoinEventListener listener) {
        joinListeners.remove(listener);
    }

    public void unregisterLeaveListener(PlayerLeaveEventListener listener) {
        leaveListeners.remove(listener);
    }

    public static CopyOnWriteArrayList<ChatEventListener> getChatListeners() {
        return chatListeners;
    }

    public void registerChatListener(ChatEventListener listener) {
        chatListeners.add(listener);
    }

    public void unregisterChatListener(ChatEventListener listener) {
        chatListeners.remove(listener);
    }

    @Override
    public void onPlayerChat(ChatEventListener listener) {
        registerChatListener(listener);
    }

    @Override
    public void onPlayerJoin(PlayerJoinEventListener listener) {
        registerJoinListener(listener);
    }

    @Override
    public void onPlayerLeave(PlayerLeaveEventListener listener) {
        registerLeaveListener(listener);
    }

    private static class MinecraftPlayerJoinEvent implements PlayerJoinEvent {
        private final IPlayer player;
        private IComponent joinMessage;

        public MinecraftPlayerJoinEvent(ServerPlayerEntity player) {
            this.player = MinecraftPlayer.of(player);
        }

        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public IComponent getJoinMessage() {
            return joinMessage;
        }

        @Override
        public void setJoinMessage(IComponent message) {
            this.joinMessage = message;
        }
    }

    private static class MinecraftPlayerLeaveEvent implements PlayerLeaveEvent {
        private final IPlayer player;
        private IComponent leaveMessage;

        public MinecraftPlayerLeaveEvent(ServerPlayerEntity player) {
            this.player = MinecraftPlayer.of(player);
        }

        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public IComponent getLeaveMessage() {
            return leaveMessage;
        }

        @Override
        public void setLeaveMessage(IComponent message) {
            this.leaveMessage = message;
        }
    }

    public static class ChatEventImpl implements ChatEvent {
        private final IPlayer player;
        private String message;
        private boolean cancelled = false;

        public ChatEventImpl(ServerPlayerEntity player, String message) {
            this.player = MinecraftPlayer.of(player);
            this.message = message;
        }

        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public void setMessage(String message) {
            this.message = message;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}