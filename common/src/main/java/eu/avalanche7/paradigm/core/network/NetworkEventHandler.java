package eu.avalanche7.paradigm.core.network;

import java.util.function.BooleanSupplier;

@FunctionalInterface
public interface NetworkEventHandler {

    void onRemoteEvent(NetworkEvent event);

    default void onRemoteEvent(NetworkEvent event, BooleanSupplier current) {
        if (current.getAsBoolean()) onRemoteEvent(event);
    }
}
