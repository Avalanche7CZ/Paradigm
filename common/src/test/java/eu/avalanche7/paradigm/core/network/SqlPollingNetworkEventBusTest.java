package eu.avalanche7.paradigm.core.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

class SqlPollingNetworkEventBusTest {

    private static final long EVENT_TIME = System.currentTimeMillis() + 60_000L;
    private static final String CHANNEL = "paradigm:test";

    @Test
    void deliversRemoteEventsOnceAndNeverRedeliversThem() {
        RecordingSource source = new RecordingSource();
        SqlPollingNetworkEventBus bus = new SqlPollingNetworkEventBus(null, () -> 5);
        List<NetworkEvent> delivered = new CopyOnWriteArrayList<>();
        bus.registerSource(source);
        bus.start();
        bus.subscribe(CHANNEL, delivered::add);

        source.events.add(event("e1", "beta", 100L));
        source.events.add(event("e2", "beta", 100L));
        assertEquals(2, bus.poll());

        assertEquals(0, bus.poll(), "the same batch must not be redelivered");

        source.events.add(event("e3", "beta", 200L));
        assertEquals(1, bus.poll());
        assertEquals(List.of("e1", "e2", "e3"), delivered.stream().map(NetworkEvent::eventId).toList());
    }

    @Test
    void locallyOriginatedEventsAreNeverDeliveredBack() {
        RecordingSource source = new RecordingSource();
        SqlPollingNetworkEventBus bus = new LocalServerBus("alpha");
        List<NetworkEvent> delivered = new CopyOnWriteArrayList<>();
        bus.registerSource(source);
        bus.start();
        bus.subscribe(CHANNEL, delivered::add);

        source.events.add(event("local-1", "alpha", 100L));
        source.events.add(event("remote-1", "beta", 100L));

        assertEquals(1, bus.poll());
        assertEquals(1, delivered.size());
        assertEquals("remote-1", delivered.get(0).eventId());
    }

    @Test
    void repeatedPollsOfUnchangedStateAreIdempotent() {
        RecordingSource source = new RecordingSource();
        SqlPollingNetworkEventBus bus = new SqlPollingNetworkEventBus(null, () -> 5);
        List<NetworkEvent> delivered = new CopyOnWriteArrayList<>();
        bus.registerSource(source);
        bus.start();
        bus.subscribe(CHANNEL, delivered::add);

        source.events.add(event("e1", "beta", 100L));
        bus.poll();
        for (int i = 0; i < 10; i++) {
            assertEquals(0, bus.poll());
        }
        assertEquals(1, delivered.size());
    }

    @Test
    void paginatesEventsCreatedInTheSameMillisecond() {
        RecordingSource source = new RecordingSource();
        SqlPollingNetworkEventBus bus = new SqlPollingNetworkEventBus(null, () -> 5);
        List<NetworkEvent> delivered = new CopyOnWriteArrayList<>();
        bus.registerSource(source);
        bus.start();
        bus.subscribe(CHANNEL, delivered::add);

        for (int i = 0; i < 201; i++) {
            source.events.add(event(String.format("e%03d", i), "beta", 100L));
        }

        assertEquals(200, bus.poll());
        assertEquals(1, bus.poll());
        assertEquals(201, delivered.size());
    }

    @Test
    void unavailableSourceYieldsNothingAndKeepsTheBusUnnetworked() {
        RecordingSource source = new RecordingSource();
        source.available = false;
        SqlPollingNetworkEventBus bus = new SqlPollingNetworkEventBus(null, () -> 5);
        bus.registerSource(source);

        assertEquals(0, bus.poll());
        assertFalse(bus.isNetworked());
        bus.start();
        assertFalse(bus.isRunning(), "an unavailable source must not start a poll loop");
    }

    @Test
    void duplicateSourceRegistrationForOneChannelIsIgnored() {
        SqlPollingNetworkEventBus bus = new SqlPollingNetworkEventBus(null, () -> 5);
        RecordingSource first = new RecordingSource();
        RecordingSource second = new RecordingSource();
        bus.registerSource(first);
        bus.registerSource(second);
        bus.start();
        List<NetworkEvent> delivered = new CopyOnWriteArrayList<>();
        bus.subscribe(CHANNEL, delivered::add);

        first.events.add(event("e1", "beta", 100L));
        second.events.add(event("e1", "beta", 100L));
        bus.poll();
        assertTrue(delivered.size() <= 1);
    }

    @Test
    void unsubscribeStopsDelivery() {
        RecordingSource source = new RecordingSource();
        SqlPollingNetworkEventBus bus = new SqlPollingNetworkEventBus(null, () -> 5);
        List<NetworkEvent> delivered = new ArrayList<>();
        NetworkEventHandler handler = delivered::add;
        bus.registerSource(source);
        bus.start();
        bus.subscribe(CHANNEL, handler);
        bus.unsubscribe(CHANNEL, handler);

        source.events.add(event("e1", "beta", 100L));
        bus.poll();
        assertTrue(delivered.isEmpty());
    }

    @Test
    void aFailingHandlerDoesNotStopOtherHandlers() {
        RecordingSource source = new RecordingSource();
        SqlPollingNetworkEventBus bus = new SqlPollingNetworkEventBus(null, () -> 5);
        List<NetworkEvent> delivered = new CopyOnWriteArrayList<>();
        bus.registerSource(source);
        bus.start();
        bus.subscribe(CHANNEL, event -> {
            throw new IllegalStateException("handler failure");
        });
        bus.subscribe(CHANNEL, delivered::add);

        source.events.add(event("e1", "beta", 100L));
        bus.poll();
        assertEquals(1, delivered.size());
    }

    private static NetworkEvent event(String id, String origin, long createdAtMs) {
        return new NetworkEvent(CHANNEL, id, "net", origin, "CREATED", EVENT_TIME + createdAtMs,
                Map.of("ticketKey", "T-1"));
    }

    private static final class LocalServerBus extends SqlPollingNetworkEventBus {
        private final String serverId;

        private LocalServerBus(String serverId) {
            super(null, () -> 5);
            this.serverId = serverId;
        }

        @Override
        protected String resolveLocalServerId() {
            return serverId;
        }
    }

    private static final class RecordingSource implements NetworkEventSource {
        private final List<NetworkEvent> events = new ArrayList<>();
        private boolean available = true;

        @Override
        public String channel() {
            return CHANNEL;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<NetworkEvent> fetchSince(long sinceMs, String afterEventId, String excludeServerId, int limit) {
            List<NetworkEvent> matching = new ArrayList<>();
            for (NetworkEvent event : events) {
                if (event.createdAtMs() > sinceMs
                        || (event.createdAtMs() == sinceMs && afterEventId != null
                        && event.eventId().compareTo(afterEventId) > 0)) {
                    matching.add(event);
                }
            }
            return matching.subList(0, Math.min(limit, matching.size()));
        }
    }
}
