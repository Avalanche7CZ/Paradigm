package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.modules.permissions.PermissionNodeRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;

import java.util.concurrent.CopyOnWriteArrayList;

public class MinecraftEventSystem implements IEventSystem {

    private final CopyOnWriteArrayList<ChatEventListener> chatListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerJoinEventListener> joinListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerLeaveEventListener> leaveListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerDeathEventListener> deathListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerCommandEventListener> commandListeners = new CopyOnWriteArrayList<>();

    @Override
    public void onPlayerChat(ChatEventListener listener) {
        if (listener != null) chatListeners.add(listener);
    }

    @Override
    public void onPlayerJoin(PlayerJoinEventListener listener) {
        if (listener != null) joinListeners.add(listener);
    }

    @Override
    public void onPlayerLeave(PlayerLeaveEventListener listener) {
        if (listener != null) leaveListeners.add(listener);
    }

    @Override
    public void onPlayerDeath(PlayerDeathEventListener listener) {
        if (listener != null) deathListeners.add(listener);
    }

    @Override
    public void onPlayerCommand(PlayerCommandEventListener listener) {
        if (listener != null) commandListeners.add(listener);
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
                System.err.println("[Paradigm-NeoForge] Error in player join event listener: " + e.getMessage());
            }
        }

        // IMPORTANT:
        // NeoForge doesn't let us override the vanilla join message via this event.
        // Our common JoinLeaveMessages module calls event.setJoinMessage(...) and then falls back to
        // platform.broadcastSystemMessage(...) only if setJoinMessage throws.
        // Because our setJoinMessage is a no-op, nothing gets broadcast.
        // So we do the Fabric-style fallback here when at least one module asked for a custom message.
        try {
            IComponent custom = joinEvent.getJoinMessage();
            if (custom != null) {
                Services services = Paradigm.getServices();
                if (services != null) {
                    IPlatformAdapter platform = services.getPlatformAdapter();
                    if (platform != null) {
                        platform.broadcastSystemMessage(custom);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            System.out.println("[Paradigm-NeoForge] Join event fired for: " + joinEvent.getPlayer().getName());
        } catch (Throwable ignored) {}
    }

    public void dispatchLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (leaveListeners.isEmpty()) return;
        MinecraftPlayerLeaveEvent leaveEvent = new MinecraftPlayerLeaveEvent(event);
        for (PlayerLeaveEventListener listener : leaveListeners) {
            try {
                listener.onPlayerLeave(leaveEvent);
            } catch (Exception e) {
                System.err.println("[Paradigm-NeoForge] Error in player leave event listener: " + e.getMessage());
            }
        }

        // Same reasoning as join: broadcast custom leave message if a module set it.
        try {
            IComponent custom = leaveEvent.getLeaveMessage();
            if (custom != null) {
                Services services = Paradigm.getServices();
                if (services != null) {
                    IPlatformAdapter platform = services.getPlatformAdapter();
                    if (platform != null) {
                        platform.broadcastSystemMessage(custom);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            System.out.println("[Paradigm-NeoForge] Leave event fired for: " + leaveEvent.getPlayer().getName());
        } catch (Throwable ignored) {}
    }

    public void dispatchDeath(LivingDeathEvent event) {
        if (deathListeners.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftPlayerDeathEvent deathEvent = new MinecraftPlayerDeathEvent(player);
        for (PlayerDeathEventListener listener : deathListeners) {
            try {
                listener.onPlayerDeath(deathEvent);
            } catch (Exception e) {
                System.err.println("[Paradigm-NeoForge] Error in player death event listener: " + e.getMessage());
            }
        }
    }

    public void dispatchCommand(CommandEvent event) {
        if (commandListeners.isEmpty()) return;
        ServerPlayer player = commandPlayer(event);
        if (player == null) return;

        MinecraftCommandEvent commandEvent = new MinecraftCommandEvent(player, commandString(event));
        for (PlayerCommandEventListener listener : commandListeners) {
            try {
                listener.onPlayerCommand(commandEvent);
            } catch (Exception e) {
                System.err.println("[Paradigm-NeoForge] Error in command event listener: " + e.getMessage());
            }
        }
        if (commandEvent.isCancelled()) {
            event.setCanceled(true);
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
    public void onPlayerDeath(LivingDeathEvent event) {
        dispatchDeath(event);
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        dispatchCommand(event);
    }

    @SubscribeEvent
    public void onPermissionHandler(PermissionGatherEvent.Handler event) {
        Services services = Paradigm.getServices();
        if (services == null || services.getPermissionsHandler() == null || !services.getPermissionsHandler().shouldRegisterForgePermissionHandler()) {
            return;
        }
        event.addPermissionHandler(ParadigmNeoForgePermissionHandler.IDENTIFIER, ParadigmNeoForgePermissionHandler::new);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        for (PermissionNode<?> node : event.getNodes()) {
            registerNeoForgePermissionNode(node);
        }
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer sp = event.getPlayer();
        String raw = event.getMessage().getString();
        DispatchResult result = dispatchChatRaw(sp, raw);

        try {
            System.out.println("[Paradigm-NeoForge] Chat: " + (sp != null ? sp.getGameProfile().getName() : "<null>") + " raw='" + raw + "' -> msg='" + result.message + "' cancelled=" + result.cancelled);
        } catch (Throwable ignored) {}

        // If a module cancelled or modified the message, cancel the original NeoForge chat event.
        if (result.cancelled || !raw.equals(result.message)) {
            event.setCanceled(true);

            // If the message was cancelled (e.g., redirected into staffchat/groupchat), do not rebroadcast.
            if (result.cancelled) return;

            // Message was modified: re-broadcast the modified text so players actually see it.
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    MutableComponent comp = net.minecraft.network.chat.Component.literal(result.message);
                    server.getPlayerList().broadcastSystemMessage(comp, false);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static class MinecraftPlayerJoinEvent implements PlayerJoinEvent {
        private final IPlayer player;
        private IComponent joinMessage;

        public MinecraftPlayerJoinEvent(PlayerEvent.PlayerLoggedInEvent neoforgeEvent) {
            this.player = MinecraftPlayer.of((ServerPlayer) neoforgeEvent.getEntity());
        }
        @Override public IPlayer getPlayer() { return player; }
        @Override public IComponent getJoinMessage() { return joinMessage; }
        @Override public void setJoinMessage(IComponent message) {
            // Store for post-dispatch fallback broadcast (NeoForge doesn't provide a setter here).
            this.joinMessage = message;
        }
    }

    private static class MinecraftPlayerLeaveEvent implements PlayerLeaveEvent {
        private final IPlayer player;
        private IComponent leaveMessage;

        public MinecraftPlayerLeaveEvent(PlayerEvent.PlayerLoggedOutEvent neoforgeEvent) {
            this.player = MinecraftPlayer.of((ServerPlayer) neoforgeEvent.getEntity());
        }
        @Override public IPlayer getPlayer() { return player; }
        @Override public IComponent getLeaveMessage() { return leaveMessage; }
        @Override public void setLeaveMessage(IComponent message) {
            // Store for post-dispatch fallback broadcast.
            this.leaveMessage = message;
        }
    }

    private static class MinecraftPlayerDeathEvent implements PlayerDeathEvent {
        private final IPlayer player;

        public MinecraftPlayerDeathEvent(ServerPlayer player) {
            this.player = MinecraftPlayer.of(player);
        }

        @Override public IPlayer getPlayer() { return player; }
    }

    private static ServerPlayer commandPlayer(CommandEvent event) {
        try {
            Object source = event.getParseResults().getContext().getSource();
            if (source instanceof CommandSourceStack stack) {
                return stack.getPlayerOrException();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String commandString(CommandEvent event) {
        try {
            String raw = event.getParseResults().getReader().getString();
            return raw != null ? raw : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static class MinecraftCommandEvent implements PlayerCommandEvent {
        private final IPlayer player;
        private final String command;
        private boolean cancelled;

        MinecraftCommandEvent(ServerPlayer player, String command) {
            this.player = MinecraftPlayer.of(player);
            this.command = command;
        }

        @Override public IPlayer getPlayer() { return player; }
        @Override public String getCommand() { return command; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    }

    private static void registerNeoForgePermissionNode(PermissionNode<?> node) {
        Services services = Paradigm.getServices();
        if (services == null || services.getPermissionsHandler() == null || node == null) {
            return;
        }
        services.getPermissionsHandler().registerExternalPermissionNode(
                node.getNodeName(),
                PermissionNodeRegistry.SOURCE_NEOFORGE_PERMISSION_API,
                permissionDescription(node),
                -1
        );
    }

    private static String permissionDescription(PermissionNode<?> node) {
        try {
            if (node.getDescription() != null) return node.getDescription().getString();
            if (node.getReadableName() != null) return node.getReadableName().getString();
        } catch (Throwable ignored) {
        }
        return "";
    }
}
