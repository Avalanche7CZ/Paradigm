package eu.avalanche7.paradigm.modules.dashboard.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.modules.dashboard.DashboardConfig;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.repository.DashboardLoginRepository;

public class DashboardAuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] LOGIN_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final Map<String, DashboardLoginToken> loginTokens = new ConcurrentHashMap<>();
    private final Map<String, DashboardSession> sessions = new ConcurrentHashMap<>();

    private final Supplier<SharedStorage> sharedStorage;
    private final Predicate<DashboardPrincipal> allowed;
    private final java.util.function.LongSupplier clock;

    public DashboardAuthService() { this(() -> null, principal -> true); }

    public DashboardAuthService(Supplier<SharedStorage> sharedStorage, Predicate<DashboardPrincipal> allowed) {
        this(sharedStorage, allowed, System::currentTimeMillis);
    }

    DashboardAuthService(Supplier<SharedStorage> sharedStorage, Predicate<DashboardPrincipal> allowed, java.util.function.LongSupplier clock) {
        this.sharedStorage = sharedStorage;
        this.allowed = allowed;
        this.clock = clock;
    }

    public record SharedStorage(DashboardLoginRepository repository, ServerIdentity identity) {}

    public IssuedToken createSharedLoginToken(DashboardPrincipal principal, DashboardConfig config, String host) {
        if (principal == null || principal.console()) throw new IllegalArgumentException("Cross-server login links require a player. Use the Dashboard host for console login.");
        String uuid = UUID.fromString(principal.uuid()).toString();
        if (principal.name() == null || principal.name().isBlank()) throw new IllegalArgumentException("Dashboard player identity is unavailable.");
        SharedStorage storage = sharedStorage.get();
        if (storage == null) throw new StorageException("Cross-server Dashboard login requires active shared MySQL/MariaDB storage.");
        String raw = loginCode();
        long expires = storage.repository().issue(hash(normalizeLoginToken(raw)), storage.identity().networkId(),
                storage.identity().serverId(), host, uuid, principal.name(), minutes(Math.min(10, config.loginTokenMinutes)));
        return new IssuedToken(raw, expires);
    }

    public IssuedToken createLoginToken(DashboardPrincipal principal, DashboardConfig config) {
        String raw = loginCode();
        long now = clock.getAsLong();
        String hash = hash(normalizeLoginToken(raw));
        loginTokens.put(hash, new DashboardLoginToken(hash, principal, now, now + minutes(config.loginTokenMinutes), false));
        cleanup(now);
        return new IssuedToken(raw, now + minutes(config.loginTokenMinutes));
    }

    public IssuedSession consumeLoginToken(String rawToken, DashboardConfig config) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }

        long now = clock.getAsLong();
        String hash = hash(normalizeLoginToken(rawToken));
        DashboardLoginToken[] consumed = new DashboardLoginToken[1];

        loginTokens.compute(hash, (key, current) -> {
            if (current == null || current.used() || current.expired(now)) {
                return current;
            }
            consumed[0] = current;
            return current.markUsed();
        });

        DashboardLoginToken token = consumed[0];
        DashboardPrincipal principal = token != null ? token.principal() : null;
        if (principal == null) {
            SharedStorage storage = sharedStorage.get();
            if (storage != null) {
                var grant = storage.repository().consume(hash, storage.identity().networkId(), storage.identity().serverId());
                if (grant != null) {
                    try {
                        String uuid = UUID.fromString(grant.playerUuid()).toString();
                        if (grant.playerName() != null && !grant.playerName().isBlank()) principal = new DashboardPrincipal(uuid, grant.playerName(), false);
                    } catch (IllegalArgumentException invalidIdentity) {
                        return null;
                    }
                }
            }
        }
        if (principal == null || !allowed.test(principal)) {
            cleanup(now);
            return null;
        }

        String rawSession = randomToken(32);
        String rawCsrf = randomToken(32);
        String sessionHash = hash(rawSession);
        DashboardSession session = new DashboardSession(sessionHash, rawCsrf, principal, now, now + minutes(config.sessionMinutes));
        sessions.put(sessionHash, session);
        cleanup(now);
        return new IssuedSession(rawSession, rawCsrf, session);
    }

    public DashboardSession validateSession(String rawSession) {
        if (rawSession == null || rawSession.isBlank()) {
            return null;
        }
        long now = clock.getAsLong();
        DashboardSession session = sessions.get(hash(rawSession.trim()));
        if (session == null || session.expired(now)) {
            cleanup(now);
            return null;
        }
        return session;
    }

    public void logout(String rawSession) {
        if (rawSession != null && !rawSession.isBlank()) {
            sessions.remove(hash(rawSession.trim()));
        }
    }

    public boolean validateCsrf(DashboardSession session, String rawCsrf) {
        return session != null
                && rawCsrf != null
                && !rawCsrf.isBlank()
                && session.csrfToken() != null
                && MessageDigest.isEqual(session.csrfToken().getBytes(StandardCharsets.UTF_8), rawCsrf.trim().getBytes(StandardCharsets.UTF_8));
    }

    public void cleanup(long now) {
        removeExpired(loginTokens, now);
        Iterator<Map.Entry<String, DashboardSession>> sessionsIt = sessions.entrySet().iterator();
        while (sessionsIt.hasNext()) {
            if (sessionsIt.next().getValue().expired(now)) {
                sessionsIt.remove();
            }
        }
    }

    public int activeLoginTokenCount() {
        cleanup(clock.getAsLong());
        return loginTokens.size();
    }

    public int activeSessionCount() {
        cleanup(clock.getAsLong());
        return sessions.size();
    }

    private static void removeExpired(Map<String, DashboardLoginToken> map, long now) {
        Iterator<Map.Entry<String, DashboardLoginToken>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            DashboardLoginToken token = it.next().getValue();
            if (token.used() || token.expired(now)) {
                it.remove();
            }
        }
    }

    public static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String normalizeLoginToken(String rawToken) {
        return rawToken == null ? "" : rawToken.replace("-", "").replace(" ", "").trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String loginCode() {
        StringBuilder code = new StringBuilder(19);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                code.append('-');
            }
            code.append(LOGIN_CODE_ALPHABET[RANDOM.nextInt(LOGIN_CODE_ALPHABET.length)]);
        }
        return code.toString();
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static long minutes(int minutes) {
        return Math.max(1, minutes) * 60_000L;
    }

    public record IssuedToken(String token, long expiresAtMs) {
    }

    public record IssuedSession(String token, String csrfToken, DashboardSession session) {
    }
}
