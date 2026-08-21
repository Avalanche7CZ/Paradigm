package eu.avalanche7.paradigm.core.network;

public interface NetworkEventBus {

    void registerSource(NetworkEventSource source);

    void subscribe(String channel, NetworkEventHandler handler);

    void unsubscribe(String channel, NetworkEventHandler handler);

    void start();

    void stop();

    boolean isRunning();

    default boolean isNetworked() {
        return false;
    }
}
