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
    interface ChatEventListener {
        void onPlayerChat(ChatEvent event);
    }
    interface PlayerJoinEventListener {
        void onPlayerJoin(PlayerJoinEvent event);
    }
    interface PlayerLeaveEventListener {
        void onPlayerLeave(PlayerLeaveEvent event);
    }

    /** Register chat listener (called for every player chat message). */
    void onPlayerChat(ChatEventListener listener);

    /** Register player join listener. */
    void onPlayerJoin(PlayerJoinEventListener listener);

    /** Register player leave listener. */
    void onPlayerLeave(PlayerLeaveEventListener listener);
}
