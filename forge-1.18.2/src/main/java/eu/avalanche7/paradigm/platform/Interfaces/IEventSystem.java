package eu.avalanche7.paradigm.platform.Interfaces;

/**
 * Platform-independent event system abstraction
 */
public interface IEventSystem {

    /**
     * Player chat event data
     */
    interface ChatEvent {
        IPlayer getPlayer();
        String getMessage();
        void setMessage(String message);
        boolean isCancelled();
        void setCancelled(boolean cancelled);
    }

    /**
     * Player join event data
     */
    interface PlayerJoinEvent {
        IPlayer getPlayer();
        IComponent getJoinMessage();
        void setJoinMessage(IComponent message);
    }

    /**
     * Player leave event data
     */
    interface PlayerLeaveEvent {
        IPlayer getPlayer();
        IComponent getLeaveMessage();
        void setLeaveMessage(IComponent message);
    }

    /**
     * Event listener interfaces
     */
    interface ChatEventListener {
        void onPlayerChat(ChatEvent event);
    }

    interface PlayerJoinEventListener {
        void onPlayerJoin(PlayerJoinEvent event);
    }

    interface PlayerLeaveEventListener {
        void onPlayerLeave(PlayerLeaveEvent event);
    }

    void registerChatListener(ChatEventListener listener);
    void registerJoinListener(PlayerJoinEventListener listener);
    void registerLeaveListener(PlayerLeaveEventListener listener);

    void unregisterChatListener(ChatEventListener listener);
    void unregisterJoinListener(PlayerJoinEventListener listener);
    void unregisterLeaveListener(PlayerLeaveEventListener listener);
}
