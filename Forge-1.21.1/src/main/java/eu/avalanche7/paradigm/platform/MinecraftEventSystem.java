package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.permissions.PermissionNodeRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;

import java.util.concurrent.CopyOnWriteArrayList;

public class MinecraftEventSystem implements IEventSystem {

    private final CopyOnWriteArrayList<ChatEventListener> chatListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerJoinEventListener> joinListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerLeaveEventListener> leaveListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerDeathEventListener> deathListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerCommandEventListener> commandListeners = new CopyOnWriteArrayList<>();

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

    @Override
    public void onPlayerDeath(PlayerDeathEventListener listener) {
        deathListeners.add(listener);
    }

    @Override
    public void onPlayerCommand(PlayerCommandEventListener listener) {
        commandListeners.add(listener);
    }

    public void registerChatListener(ChatEventListener listener) {
        onPlayerChat(listener);
    }

    public void registerJoinListener(PlayerJoinEventListener listener) {
        onPlayerJoin(listener);
    }

    public void registerLeaveListener(PlayerLeaveEventListener listener) {
        onPlayerLeave(listener);
    }

    public void unregisterChatListener(ChatEventListener listener) {
        chatListeners.remove(listener);
    }

    public void unregisterJoinListener(PlayerJoinEventListener listener) {
        joinListeners.remove(listener);
    }

    public void unregisterLeaveListener(PlayerLeaveEventListener listener) {
        leaveListeners.remove(listener);
    }

    public void unregisterDeathListener(PlayerDeathEventListener listener) {
        deathListeners.remove(listener);
    }

    public void unregisterCommandListener(PlayerCommandEventListener listener) {
        commandListeners.remove(listener);
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (commandListeners.isEmpty()) return;

        ServerPlayer player = commandPlayer(event);
        if (player == null) return;

        MinecraftCommandEvent commandEvent = new MinecraftCommandEvent(player, commandString(event));
        for (PlayerCommandEventListener listener : commandListeners) {
            try {
                listener.onPlayerCommand(commandEvent);
            } catch (Exception e) {
                System.err.println("Error in command event listener: " + e.getMessage());
            }
        }
        if (commandEvent.isCancelled()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPermissionHandler(PermissionGatherEvent.Handler event) {
        Services services = Paradigm.getServices();
        if (services == null || services.getPermissionsHandler() == null || !services.getPermissionsHandler().shouldRegisterForgePermissionHandler()) {
            return;
        }
        event.addPermissionHandler(ParadigmForgePermissionHandler.IDENTIFIER, ParadigmForgePermissionHandler::new);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        for (PermissionNode<?> node : event.getNodes()) {
            registerForgePermissionNode(node);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerChat(ServerChatEvent event) {
        if (chatListeners.isEmpty()) return;

        MinecraftChatEvent chatEvent = new MinecraftChatEvent(event);
        for (ChatEventListener listener : chatListeners) {
            try {
                listener.onPlayerChat(chatEvent);
            } catch (Exception e) {
                System.err.println("Error in chat event listener: " + e.getMessage());
            }
            if (chatEvent.isCancelled()) break;
        }

        if (!chatEvent.isCancelled() && chatEvent.isModified()) {
            event.setCanceled(true);
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(chatEvent.getMessage()), false);
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
            } catch (Exception e) {
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
            } catch (Exception e) {
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
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (deathListeners.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftPlayerDeathEvent deathEvent = new MinecraftPlayerDeathEvent(player);
        for (PlayerDeathEventListener listener : deathListeners) {
            try {
                listener.onPlayerDeath(deathEvent);
            } catch (Exception e) {
                System.err.println("Error in player death event listener: " + e.getMessage());
            }
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
                initial = forgeEvent.getMessage().getString();
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
            if (message == null || message.equals(getMessage())) {
                return;
            }
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

    private static class MinecraftPlayerDeathEvent implements PlayerDeathEvent {
        private final IPlayer player;

        public MinecraftPlayerDeathEvent(ServerPlayer player) {
            this.player = MinecraftPlayer.of(player);
        }

        @Override
        public IPlayer getPlayer() {
            return player;
        }
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

        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public String getCommand() {
            return command;
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

    private static void registerForgePermissionNode(PermissionNode<?> node) {
        Services services = Paradigm.getServices();
        if (services == null || services.getPermissionsHandler() == null || node == null) {
            return;
        }
        services.getPermissionsHandler().registerExternalPermissionNode(
                node.getNodeName(),
                PermissionNodeRegistry.SOURCE_FORGE_PERMISSION_API,
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
