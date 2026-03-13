package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.concurrent.CopyOnWriteArrayList;

public class MinecraftEventSystem implements IEventSystem {

    private final CopyOnWriteArrayList<ChatEventListener> chatListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerJoinEventListener> joinListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerLeaveEventListener> leaveListeners = new CopyOnWriteArrayList<>();

    /** Called from PlatformAdapterImpl constructor to register on Forge 61.x buses directly. */
    public void register() {
        // Chat - CancellableEventBus: Predicate returning true = cancel
        ServerChatEvent.BUS.addListener((ServerChatEvent event) -> {
            MinecraftChatEvent chatEvent = new MinecraftChatEvent(event);
            if (!chatListeners.isEmpty()) {
                for (ChatEventListener listener : chatListeners) {
                    try { listener.onPlayerChat(chatEvent); } catch (Exception e) {
                        System.err.println("Error in chat event listener: " + e.getMessage());
                    }
                }
            }
            if (chatEvent.isCancelled()) return true; // cancel the Forge event
            if (paradigm$handleCustomChatFormat(chatEvent)) {
                return true;
            }
            if (chatEvent.isModified()) {
                event.setMessage(net.minecraft.network.chat.Component.literal(chatEvent.getMessage()));
            }
            return false;
        });

        // Join
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (joinListeners.isEmpty()) return;
            MinecraftPlayerJoinEvent joinEvent = new MinecraftPlayerJoinEvent(event);
            for (PlayerJoinEventListener listener : joinListeners) {
                try { listener.onPlayerJoin(joinEvent); } catch (Exception e) {
                    System.err.println("Error in player join event listener: " + e.getMessage());
                }
            }
            try {
                IComponent custom = joinEvent.getJoinMessage();
                if (custom != null) {
                    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                    if (server != null) {
                        server.getPlayerList().broadcastSystemMessage(((MinecraftComponent) custom).getHandle(), false);
                    }
                }
            } catch (Throwable ignored) {}
        });

        // Leave
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (leaveListeners.isEmpty()) return;
            MinecraftPlayerLeaveEvent leaveEvent = new MinecraftPlayerLeaveEvent(event);
            for (PlayerLeaveEventListener listener : leaveListeners) {
                try { listener.onPlayerLeave(leaveEvent); } catch (Exception e) {
                    System.err.println("Error in player leave event listener: " + e.getMessage());
                }
            }
            try {
                IComponent custom = leaveEvent.getLeaveMessage();
                if (custom != null) {
                    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                    if (server != null) {
                        server.getPlayerList().broadcastSystemMessage(((MinecraftComponent) custom).getHandle(), false);
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    @Override
    public void onPlayerChat(ChatEventListener listener) { chatListeners.add(listener); }
    @Override
    public void onPlayerJoin(PlayerJoinEventListener listener) { joinListeners.add(listener); }
    @Override
    public void onPlayerLeave(PlayerLeaveEventListener listener) { leaveListeners.add(listener); }

    public void registerChatListener(ChatEventListener listener) { onPlayerChat(listener); }
    public void registerJoinListener(PlayerJoinEventListener listener) { onPlayerJoin(listener); }
    public void registerLeaveListener(PlayerLeaveEventListener listener) { onPlayerLeave(listener); }
    public void unregisterChatListener(ChatEventListener listener) { chatListeners.remove(listener); }
    public void unregisterJoinListener(PlayerJoinEventListener listener) { joinListeners.remove(listener); }
    public void unregisterLeaveListener(PlayerLeaveEventListener listener) { leaveListeners.remove(listener); }

    private boolean paradigm$handleCustomChatFormat(MinecraftChatEvent chatEvent) {
        try {
            Services services = Paradigm.getServices();
            if (services == null) return false;

            ChatConfigHandler.Config cfg = services.getChatConfig();
            if (cfg == null || !Boolean.TRUE.equals(cfg.enableCustomChatFormat.get())) {
                return false;
            }

            String customFormat = cfg.customChatFormat.get();
            if (customFormat == null || customFormat.isBlank()) {
                return false;
            }

            IPlayer sender = chatEvent.getPlayer();
            String messageText = chatEvent.getMessage();
            String formattedText = customFormat.replace("{message}", messageText != null ? messageText : "");
            IComponent parsedComponent = services.getMessageParser().parseMessage(formattedText, sender);

            net.minecraft.network.chat.Component finalMessage = parsedComponent instanceof MinecraftComponent mc
                    ? mc.getHandle()
                    : net.minecraft.network.chat.Component.literal(formattedText);

            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return false;
            }

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(finalMessage);
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[Paradigm-Forge] Failed to apply custom chat format: " + t.getMessage());
            return false;
        }
    }

    // ---- inner event wrappers ----

    private static class MinecraftChatEvent implements ChatEvent {
        private final ServerChatEvent forgeEvent;
        private final IPlayer player;
        private final String original;
        private String msg;
        private boolean cancelled;

        public MinecraftChatEvent(ServerChatEvent forgeEvent) {
            this.forgeEvent = forgeEvent;
            this.player = MinecraftPlayer.of((ServerPlayer) forgeEvent.getPlayer());
            String initial;
            try { initial = forgeEvent.getRawText(); } catch (Throwable t) { initial = ""; }
            this.original = initial;
            this.msg = initial;
        }

        boolean isModified() { return msg != null && !msg.equals(original); }

        @Override public IPlayer getPlayer() { return player; }
        @Override public String getMessage() { return msg; }
        @Override public void setMessage(String message) { if (message != null && !message.equals(getMessage())) this.msg = message; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    }

    private static class MinecraftPlayerJoinEvent implements PlayerJoinEvent {
        private final IPlayer player;
        private IComponent joinMessage;
        public MinecraftPlayerJoinEvent(PlayerEvent.PlayerLoggedInEvent e) {
            this.player = MinecraftPlayer.of((ServerPlayer) e.getEntity());
        }
        @Override public IPlayer getPlayer() { return player; }
        @Override public IComponent getJoinMessage() { return joinMessage; }
        @Override public void setJoinMessage(IComponent message) { this.joinMessage = message; }
    }

    private static class MinecraftPlayerLeaveEvent implements PlayerLeaveEvent {
        private final IPlayer player;
        private IComponent leaveMessage;
        public MinecraftPlayerLeaveEvent(PlayerEvent.PlayerLoggedOutEvent e) {
            this.player = MinecraftPlayer.of((ServerPlayer) e.getEntity());
        }
        @Override public IPlayer getPlayer() { return player; }
        @Override public IComponent getLeaveMessage() { return leaveMessage; }
        @Override public void setLeaveMessage(IComponent message) { this.leaveMessage = message; }
    }
}
