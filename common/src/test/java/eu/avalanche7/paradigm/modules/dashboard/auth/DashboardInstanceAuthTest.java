package eu.avalanche7.paradigm.modules.dashboard.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.modules.dashboard.DashboardConfig;

class DashboardInstanceAuthTest {
    @Test void eachIndependentInstanceAcceptsOnlyItsOwnSingleUseTokenAndSession() {
        var first = new DashboardAuthService();
        var second = new DashboardAuthService();
        var config = new DashboardConfig();
        var player = new DashboardPrincipal(UUID.randomUUID().toString(), "Admin", false);
        var a = first.createLoginToken(player, config);
        var b = second.createLoginToken(player, config);
        assertNull(second.consumeLoginToken(a.token(), config));
        assertNull(first.consumeLoginToken(b.token(), config));
        var sessionA = first.consumeLoginToken(a.token(), config);
        var sessionB = second.consumeLoginToken(b.token(), config);
        assertNotNull(sessionA);
        assertNotNull(sessionB);
        assertNull(first.consumeLoginToken(a.token(), config));
        assertNull(second.consumeLoginToken(b.token(), config));
        assertNull(first.validateSession(sessionB.token()));
        assertNull(second.validateSession(sessionA.token()));
        assertTrue(first.validateCsrf(sessionA.session(), sessionA.csrfToken()));
        assertFalse(first.validateCsrf(sessionA.session(), sessionB.csrfToken()));
    }

    @Test void expiresAtTheBoundaryRejectsMalformedTokensAndCannotReplayDeniedLogin() {
        var now = new AtomicLong(1_000L);
        var allowed = new AtomicBoolean(true);
        var auth = new DashboardAuthService(() -> null, principal -> allowed.get(), now::get);
        var config = new DashboardConfig(); config.loginTokenMinutes = 1; config.sessionMinutes = 1;
        var player = new DashboardPrincipal(UUID.randomUUID().toString(), "Admin", false);
        for (String invalid : new String[]{null, "", "   ", "garbage", "<script>"}) assertNull(auth.consumeLoginToken(invalid, config));
        var expires = auth.createLoginToken(player, config);
        now.set(expires.expiresAtMs());
        assertNull(auth.consumeLoginToken(expires.token(), config));
        assertEquals(0, auth.activeSessionCount());
        var denied = auth.createLoginToken(player, config);
        allowed.set(false);
        assertNull(auth.consumeLoginToken(denied.token(), config));
        allowed.set(true);
        assertNull(auth.consumeLoginToken(denied.token(), config));
        var valid = auth.createLoginToken(player, config);
        var session = auth.consumeLoginToken(valid.token(), config);
        assertNotNull(session);
        now.set(session.session().expiresAtMs());
        assertNull(auth.validateSession(session.token()));
        assertEquals(0, auth.activeSessionCount());
    }

    @Test void concurrentRedemptionHasExactlyOneWinner() throws Exception {
        var auth = new DashboardAuthService();
        var config = new DashboardConfig();
        var token = auth.createLoginToken(new DashboardPrincipal("console", "Console", true), config);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return auth.consumeLoginToken(token.token(), config); });
            var b = pool.submit(() -> { start.await(); return auth.consumeLoginToken(token.token(), config); });
            start.countDown();
            assertNotEquals(a.get(10, java.util.concurrent.TimeUnit.SECONDS) == null, b.get(10, java.util.concurrent.TimeUnit.SECONDS) == null);
            assertEquals(1, auth.activeSessionCount());
        } finally { pool.shutdownNow(); }
    }
}
