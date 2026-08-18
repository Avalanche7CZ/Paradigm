package eu.avalanche7.paradigm.platform;

import com.mojang.logging.LogUtils;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.eventbus.api.listener.Priority;
import eu.avalanche7.paradigm.modules.permissions.PermissionNodeRegistry;
import org.slf4j.Logger;

import java.util.concurrent.CopyOnWriteArrayList;

public class MinecraftEventSystem implements IEventSystem {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final CopyOnWriteArrayList<ChatEventListener> chatListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerJoinEventListener> joinListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerLeaveEventListener> leaveListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerDeathEventListener> deathListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerCommandEventListener> commandListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerAdvancementEventListener> advancementListeners = new CopyOnWriteArrayList<>();

    public void register() {

        ServerChatEvent.BUS.addListener(Priority.HIGHEST, (ServerChatEvent event) -> {
            MinecraftChatEvent chatEvent = new MinecraftChatEvent(event);
            if (!chatListeners.isEmpty()) {
                for (ChatEventListener listener : chatListeners) {
                    try { listener.onPlayerChat(chatEvent); } catch (Exception e) {
                        System.err.println("Error in chat event listener: " + e.getMessage());
                    }
                    if (chatEvent.isCancelled()) break;
                }
            }
            if (chatEvent.isCancelled()) return true;
            if (paradigm$handleCustomChatFormat(chatEvent)) {
                return true;
            }
            if (chatEvent.isModified()) {
                event.setMessage(net.minecraft.network.chat.Component.literal(chatEvent.getMessage()));
            }
            return false;
        });

        PlayerEvent.PlayerLoggedInEvent.BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (joinListeners.isEmpty()) return;
            MinecraftPlayerJoinEvent joinEvent = new MinecraftPlayerJoinEvent(event);
            for (PlayerJoinEventListener listener : joinListeners) {
                try { listener.onPlayerJoin(joinEvent); } catch (Exception failure) {
                    LOGGER.error("[Paradigm] Player lifecycle: join event listener failed.", failure);
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
            } catch (Throwable failure) {
                LOGGER.warn("[Paradigm] Player lifecycle: failed to broadcast the custom join message.", failure);
            }
        });

        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (leaveListeners.isEmpty()) return;
            MinecraftPlayerLeaveEvent leaveEvent = new MinecraftPlayerLeaveEvent(event);
            for (PlayerLeaveEventListener listener : leaveListeners) {
                try { listener.onPlayerLeave(leaveEvent); } catch (Exception failure) {
                    LOGGER.error("Error in player leave event listener.", failure);
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
            } catch (Throwable failure) {
                LOGGER.warn("[Paradigm] Player lifecycle: failed to broadcast the custom leave message.", failure);
            }
        });

        LivingDeathEvent.BUS.addListener((LivingDeathEvent event) -> {
            if (deathListeners.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) return;
            String deathMessage = null;
            try {
                deathMessage = event.getSource().getLocalizedDeathMessage(player).getString();
            } catch (Throwable failure) {
                LOGGER.warn("[Paradigm] Player lifecycle: failed to resolve the death message.", failure);
            }
            MinecraftPlayerDeathEvent deathEvent = new MinecraftPlayerDeathEvent(player, deathMessage);
            for (PlayerDeathEventListener listener : deathListeners) {
                try { listener.onPlayerDeath(deathEvent); } catch (Exception failure) {
                    LOGGER.error("[Paradigm] Player lifecycle: death event listener failed.", failure);
                }
            }
        });

        CommandEvent.BUS.addListener((CommandEvent event) -> {
            if (commandListeners.isEmpty()) return false;
            ServerPlayer player = commandPlayer(event);
            if (player == null) return false;

            MinecraftCommandEvent commandEvent = new MinecraftCommandEvent(player, commandString(event));
            for (PlayerCommandEventListener listener : commandListeners) {
                try { listener.onPlayerCommand(commandEvent); } catch (Exception e) {
                    System.err.println("Error in command event listener: " + e.getMessage());
                }
            }
            return commandEvent.isCancelled();
        });

        AdvancementEvent.AdvancementEarnEvent.BUS.addListener((AdvancementEvent.AdvancementEarnEvent event) -> {
            if (advancementListeners.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) return;
            Advancement advancement = event.getAdvancement().value();
            DisplayInfo display = advancement.display().orElse(null);
            if (display == null || !display.shouldAnnounceChat()) return;
            MinecraftPlayerAdvancementEvent advancementEvent = new MinecraftPlayerAdvancementEvent(
                    player, display.getTitle().getString(), display.getDescription().getString());
            for (PlayerAdvancementEventListener listener : advancementListeners) {
                try { listener.onPlayerAdvancement(advancementEvent); } catch (Exception failure) {
                    LOGGER.error("[Paradigm] Player lifecycle: advancement event listener failed.", failure);
                }
            }
        });

        PermissionGatherEvent.Handler.BUS.addListener((PermissionGatherEvent.Handler event) -> {
            Services services = Paradigm.getServices();
            if (services == null || services.getPermissionsHandler() == null || !services.getPermissionsHandler().shouldRegisterForgePermissionHandler()) {
                return;
            }
            event.addPermissionHandler(ParadigmForgePermissionHandler.IDENTIFIER, ParadigmForgePermissionHandler::new);
        });

        PermissionGatherEvent.Nodes.BUS.addListener((PermissionGatherEvent.Nodes event) -> {
            for (PermissionNode<?> node : event.getNodes()) {
                registerForgePermissionNode(node);
            }
        });
    }

    @Override
    public void onPlayerChat(ChatEventListener listener) { chatListeners.add(listener); }
    @Override
    public void onPlayerJoin(PlayerJoinEventListener listener) { joinListeners.add(listener); }
    @Override
    public void onPlayerLeave(PlayerLeaveEventListener listener) { leaveListeners.add(listener); }
    @Override
    public void onPlayerDeath(PlayerDeathEventListener listener) { deathListeners.add(listener); }
    @Override
    public void onPlayerCommand(PlayerCommandEventListener listener) { commandListeners.add(listener); }
    @Override
    public void onPlayerAdvancement(PlayerAdvancementEventListener listener) { advancementListeners.add(listener); }

    public void registerChatListener(ChatEventListener listener) { onPlayerChat(listener); }
    public void registerJoinListener(PlayerJoinEventListener listener) { onPlayerJoin(listener); }
    public void registerLeaveListener(PlayerLeaveEventListener listener) { onPlayerLeave(listener); }
    public void registerDeathListener(PlayerDeathEventListener listener) { onPlayerDeath(listener); }
    public void unregisterChatListener(ChatEventListener listener) { chatListeners.remove(listener); }
    public void unregisterJoinListener(PlayerJoinEventListener listener) { joinListeners.remove(listener); }
    public void unregisterLeaveListener(PlayerLeaveEventListener listener) { leaveListeners.remove(listener); }
    public void unregisterDeathListener(PlayerDeathEventListener listener) { deathListeners.remove(listener); }
    public void unregisterCommandListener(PlayerCommandEventListener listener) { commandListeners.remove(listener); }

    private boolean paradigm$handleCustomChatFormat(MinecraftChatEvent chatEvent) {
        try {
            Services services = Paradigm.getServices();
            if (services == null) return false;

            ChatConfigHandler.Config cfg = services.getChatConfig();
            if (cfg == null || !Boolean.TRUE.equals(cfg.enableCustomChatFormat.get())) {
                return false;
            }

            IPlayer sender = chatEvent.getPlayer();
            IComponent parsedComponent = services.getChatFormatter().format(sender, chatEvent.getMessage());
            if (parsedComponent == null) {
                return false;
            }

            net.minecraft.network.chat.Component finalMessage = parsedComponent instanceof MinecraftComponent mc
                    ? mc.getHandle()
                    : net.minecraft.network.chat.Component.literal(parsedComponent.getRawText());

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

    private static class MinecraftPlayerDeathEvent implements PlayerDeathEvent {
        private final IPlayer player;
        private final String deathMessage;
        public MinecraftPlayerDeathEvent(ServerPlayer player, String deathMessage) {
            this.player = MinecraftPlayer.of(player);
            this.deathMessage = deathMessage;
        }
        @Override public IPlayer getPlayer() { return player; }
        @Override public String getDeathMessage() { return deathMessage; }
    }

    private static class MinecraftPlayerAdvancementEvent implements PlayerAdvancementEvent {
        private final IPlayer player;
        private final String advancementName;
        private final String advancementDescription;
        public MinecraftPlayerAdvancementEvent(ServerPlayer player, String advancementName, String advancementDescription) {
            this.player = MinecraftPlayer.of(player);
            this.advancementName = advancementName;
            this.advancementDescription = advancementDescription;
        }
        @Override public IPlayer getPlayer() { return player; }
        @Override public String getAdvancementName() { return advancementName; }
        @Override public String getAdvancementDescription() { return advancementDescription; }
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
