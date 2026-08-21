package eu.avalanche7.paradigm.core.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NetworkEventCursor {

    private final int seenCapacity;
    private final Map<String, Boolean> seenEventIds;
    private long cursorMs;
    private String cursorEventId;

    public NetworkEventCursor(long startAtMs, int seenCapacity) {
        this.cursorMs = startAtMs;
        this.seenCapacity = Math.max(16, seenCapacity);
        this.seenEventIds = new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > NetworkEventCursor.this.seenCapacity;
            }
        };
    }

    public synchronized long cursorMs() {
        return cursorMs;
    }

    public synchronized String cursorEventId() {
        return cursorEventId;
    }

    public synchronized void reset(long startAtMs) {
        cursorMs = startAtMs;
        cursorEventId = null;
        seenEventIds.clear();
    }

    public synchronized List<NetworkEvent> accept(List<NetworkEvent> batch) {
        List<NetworkEvent> fresh = new ArrayList<>();
        if (batch == null || batch.isEmpty()) {
            return fresh;
        }
        long highest = cursorMs;
        String highestEventId = cursorEventId;
        for (NetworkEvent event : batch) {
            if (event == null || event.eventId() == null) {
                continue;
            }
            if (event.createdAtMs() > highest) {
                highest = event.createdAtMs();
                highestEventId = event.eventId();
            } else if (event.createdAtMs() == highest
                    && (highestEventId == null || event.eventId().compareTo(highestEventId) > 0)) {
                highestEventId = event.eventId();
            }
            if (seenEventIds.putIfAbsent(event.eventId(), Boolean.TRUE) == null) {
                fresh.add(event);
            }
        }
        cursorMs = highest;
        cursorEventId = highestEventId;
        return fresh;
    }

    public synchronized int trackedCount() {
        return seenEventIds.size();
    }
}
