package eu.avalanche7.paradigm.platform;

import com.mojang.logging.LogUtils;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import java.util.concurrent.CopyOnWriteArrayList;

public class MinecraftEventSystem implements IEventSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final CopyOnWriteArrayList<PlayerJoinEventListener> joinListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerLeaveEventListener> leaveListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PlayerDeathEventListener> deathListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<ChatEventListener> chatListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<PlayerCommandEventListener> commandListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<PlayerAdvancementEventListener> advancementListeners = new CopyOnWriteArrayList<>();

    public MinecraftEventSystem() {

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (joinListeners.isEmpty()) return;
            MinecraftPlayerJoinEvent joinEvent = new MinecraftPlayerJoinEvent(player);
            for (PlayerJoinEventListener listener : joinListeners) {
                try {
                    listener.onPlayerJoin(joinEvent);
                } catch (Exception failure) {
                    LOGGER.error("[Paradigm] Player lifecycle: join event listener failed.", failure);
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
                } catch (Exception failure) {
                    LOGGER.error("Error in player leave event listener.", failure);
                }
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof ServerPlayerEntity player) || deathListeners.isEmpty()) return;
            String deathMessage = null;
            try {
                deathMessage = damageSource.getDeathMessage(player).getString();
            } catch (Throwable failure) {
                LOGGER.warn("[Paradigm] Player lifecycle: failed to resolve the death message.", failure);
            }
            MinecraftPlayerDeathEvent deathEvent = new MinecraftPlayerDeathEvent(player, deathMessage);
            for (PlayerDeathEventListener listener : deathListeners) {
                try {
                    listener.onPlayerDeath(deathEvent);
                } catch (Exception failure) {
                    LOGGER.error("[Paradigm] Player lifecycle: death event listener failed.", failure);
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

    public void registerDeathListener(PlayerDeathEventListener listener) {
        deathListeners.add(listener);
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

    public static CopyOnWriteArrayList<ChatEventListener> getChatListeners() {
        return chatListeners;
    }

    public static CopyOnWriteArrayList<PlayerCommandEventListener> getCommandListeners() {
        return commandListeners;
    }

    public void registerChatListener(ChatEventListener listener) {
        chatListeners.add(listener);
    }

    public void registerCommandListener(PlayerCommandEventListener listener) {
        commandListeners.add(listener);
    }

    public void unregisterChatListener(ChatEventListener listener) {
        chatListeners.remove(listener);
    }

    public void unregisterCommandListener(PlayerCommandEventListener listener) {
        commandListeners.remove(listener);
    }

    public static CopyOnWriteArrayList<PlayerAdvancementEventListener> getAdvancementListeners() {
        return advancementListeners;
    }

    public void registerAdvancementListener(PlayerAdvancementEventListener listener) {
        advancementListeners.add(listener);
    }

    public void unregisterAdvancementListener(PlayerAdvancementEventListener listener) {
        advancementListeners.remove(listener);
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

    @Override
    public void onPlayerDeath(PlayerDeathEventListener listener) {
        registerDeathListener(listener);
    }

    public void onPlayerCommand(PlayerCommandEventListener listener) {
        registerCommandListener(listener);
    }

    @Override
    public void onPlayerAdvancement(PlayerAdvancementEventListener listener) {
        registerAdvancementListener(listener);
    }

    private static class MinecraftPlayerDeathEvent implements PlayerDeathEvent {
        private final IPlayer player;
        private final String deathMessage;

        public MinecraftPlayerDeathEvent(ServerPlayerEntity player, String deathMessage) {
            this.player = MinecraftPlayer.of(player);
            this.deathMessage = deathMessage;
        }

        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public String getDeathMessage() {
            return deathMessage;
        }
    }

    public static class CommandEventImpl implements PlayerCommandEvent {
        private final IPlayer player;
        private final String command;
        private boolean cancelled = false;

        public CommandEventImpl(Object player, String command) {
            this.player = MinecraftPlayer.of((net.minecraft.server.network.ServerPlayerEntity) player);
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

    public static class AdvancementEventImpl implements PlayerAdvancementEvent {
        private final IPlayer player;
        private final String advancementName;
        private final String advancementDescription;

        public AdvancementEventImpl(ServerPlayerEntity player, String advancementName, String advancementDescription) {
            this.player = MinecraftPlayer.of(player);
            this.advancementName = advancementName;
            this.advancementDescription = advancementDescription;
        }

        @Override
        public IPlayer getPlayer() {
            return player;
        }

        @Override
        public String getAdvancementName() {
            return advancementName;
        }

        @Override
        public String getAdvancementDescription() {
            return advancementDescription;
        }
    }
}
