package eu.avalanche7.paradigm.modules.playtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlaytimeSessionTest {
    @Test
    void flushingConsumesOnlyTheUnaccountedDelta() {
        PlaytimeService.Session session = new PlaytimeService.Session(new PlaytimeService.Counter("Alex", 1_000L, 1_000L), 1_000L);

        session.measure(5_000L);
        assertEquals(4_000L, session.counter.measuredMs);
        session.measure(5_000L);
        assertEquals(4_000L, session.counter.measuredMs);
        session.measure(7_000L);
        assertEquals(6_000L, session.counter.measuredMs);
    }

    @Test
    void totalCombinesPersistedBaseFlushedTimeAndPendingTime() {
        PlaytimeService.Session session = new PlaytimeService.Session(new PlaytimeService.Counter("Alex", 1_000L, 1_000L), 1_000L);
        session.counter.otherBaseMs = 60_000L;

        assertEquals(64_000L, session.total(5_000L));

        session.measure(5_000L);
        assertEquals(64_000L, session.total(5_000L));
        assertEquals(66_000L, session.total(7_000L));
    }

    @Test
    void aSessionNeverCountsTimeBeforeItStarted() {
        PlaytimeService.Session session = new PlaytimeService.Session(new PlaytimeService.Counter("Alex", 1_000L, 1_000L), 10_000L);

        session.measure(5_000L);
        assertEquals(0L, session.counter.measuredMs);
        assertEquals(0L, session.total(5_000L));
    }
}
