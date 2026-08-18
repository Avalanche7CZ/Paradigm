package eu.avalanche7.paradigm.platform.Interfaces;

public interface IEventSystem {
    interface ChatEvent {
        IPlayer getPlayer();
        String getMessage();
        void setMessage(String message);
        boolean isCancelled();
        void setCancelled(boolean cancelled);
    }
    interface PlayerJoinEvent {
        IPlayer getPlayer();
        IComponent getJoinMessage();
        void setJoinMessage(IComponent message);
    }
    interface PlayerLeaveEvent {
        IPlayer getPlayer();
        IComponent getLeaveMessage();
        void setLeaveMessage(IComponent message);
    }
    interface PlayerDeathEvent {
        IPlayer getPlayer();

        default String getDeathMessage() {
            return null;
        }
    }
    interface PlayerCommandEvent {
        IPlayer getPlayer();
        String getCommand();
        boolean isCancelled();
        void setCancelled(boolean cancelled);
    }
    interface PlayerAdvancementEvent {
        IPlayer getPlayer();
        String getAdvancementName();
        String getAdvancementDescription();
    }
    interface ChatEventListener {
        void onPlayerChat(ChatEvent event);
    }
    interface PlayerJoinEventListener {
        void onPlayerJoin(PlayerJoinEvent event);
    }
    interface PlayerLeaveEventListener {
        void onPlayerLeave(PlayerLeaveEvent event);
    }
    interface PlayerDeathEventListener {
        void onPlayerDeath(PlayerDeathEvent event);
    }
    interface PlayerCommandEventListener {
        void onPlayerCommand(PlayerCommandEvent event);
    }
    interface PlayerAdvancementEventListener {
        void onPlayerAdvancement(PlayerAdvancementEvent event);
    }

    void onPlayerChat(ChatEventListener listener);

    void onPlayerJoin(PlayerJoinEventListener listener);

    void onPlayerLeave(PlayerLeaveEventListener listener);

    default void onPlayerDeath(PlayerDeathEventListener listener) {
    }

    default void onPlayerCommand(PlayerCommandEventListener listener) {
    }

    default void onPlayerAdvancement(PlayerAdvancementEventListener listener) {
    }
}
