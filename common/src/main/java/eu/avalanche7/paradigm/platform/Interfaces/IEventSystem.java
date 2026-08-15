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

    /** Register chat listener (called for every player chat message). */
    void onPlayerChat(ChatEventListener listener);

    /** Register player join listener. */
    void onPlayerJoin(PlayerJoinEventListener listener);

    /** Register player leave listener. */
    void onPlayerLeave(PlayerLeaveEventListener listener);

    /** Register player death listener. */
    default void onPlayerDeath(PlayerDeathEventListener listener) {
    }

    /** Register player command listener before the command is executed where the platform supports it. */
    default void onPlayerCommand(PlayerCommandEventListener listener) {
    }

    /** Register player advancement listener, fired once when an advancement is fully earned. */
    default void onPlayerAdvancement(PlayerAdvancementEventListener listener) {
    }
}
