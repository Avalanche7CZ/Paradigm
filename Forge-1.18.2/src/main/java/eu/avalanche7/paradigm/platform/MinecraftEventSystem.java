package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.ChatType;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

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

        if (!chatEvent.isCancelled() && chatEvent.isModified()) {
            event.setCanceled(true);
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    // 1.18 uses broadcastMessage(Component, ChatType, UUID)
                    server.getPlayerList().broadcastMessage(new TextComponent(chatEvent.getMessage()), ChatType.CHAT, java.util.UUID.randomUUID());
                }
            } catch (Throwable ignored) {
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

        try {
            IComponent custom = joinEvent.getJoinMessage();
            if (custom != null) {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    server.getPlayerList().broadcastMessage(((MinecraftComponent) custom).getHandle(), ChatType.SYSTEM, java.util.UUID.randomUUID());
                }
            }
        } catch (Throwable ignored) {
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

        try {
            IComponent custom = leaveEvent.getLeaveMessage();
            if (custom != null) {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    server.getPlayerList().broadcastMessage(((MinecraftComponent) custom).getHandle(), ChatType.SYSTEM, java.util.UUID.randomUUID());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static class MinecraftChatEvent implements ChatEvent {
        private final ServerChatEvent forgeEvent;
        private final IPlayer player;
        private final String original;
        private String msg;

        public MinecraftChatEvent(ServerChatEvent forgeEvent) {
            this.forgeEvent = forgeEvent;
            this.player = MinecraftPlayer.of((ServerPlayer) forgeEvent.getPlayer());
            String initial;
            try {
                Object m = forgeEvent.getMessage();
                initial = m != null ? String.valueOf(m) : "";
            } catch (Throwable t) {
                initial = "";
            }
            this.original = initial;
            this.msg = initial;
        }

        boolean isModified() {
            return msg != null && !msg.equals(original);
        }

        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public String getMessage() {
            return msg;
        }

        @Override
        public void setMessage(String message) {
            if (message == null) return;
            this.msg = message;
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
        private final IPlayer player;
        private IComponent joinMessage;

        public MinecraftPlayerJoinEvent(PlayerEvent.PlayerLoggedInEvent forgeEvent) {
            this.player = MinecraftPlayer.of((ServerPlayer) forgeEvent.getEntity());
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

        public MinecraftPlayerLeaveEvent(PlayerEvent.PlayerLoggedOutEvent forgeEvent) {
            this.player = MinecraftPlayer.of((ServerPlayer) forgeEvent.getEntity());
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
}
