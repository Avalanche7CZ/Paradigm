package eu.avalanche7.paradigm.core.network;

@FunctionalInterface
public interface NetworkEventHandler {

    void onRemoteEvent(NetworkEvent event);
}
