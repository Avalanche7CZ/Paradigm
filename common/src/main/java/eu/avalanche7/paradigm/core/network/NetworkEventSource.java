package eu.avalanche7.paradigm.core.network;

import java.util.List;

public interface NetworkEventSource {

    String channel();

    boolean available();

    List<NetworkEvent> fetchSince(long sinceMs, String afterEventId, String excludeServerId, int limit);
}
