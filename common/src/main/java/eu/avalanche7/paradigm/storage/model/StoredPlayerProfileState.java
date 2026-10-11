package eu.avalanche7.paradigm.storage.model;

import java.util.Map;

public record StoredPlayerProfileState(StoredPlayerProfile profile, Map<String, Long> playtimeStreams) {
    public StoredPlayerProfileState {
        playtimeStreams = Map.copyOf(playtimeStreams);
    }
}
