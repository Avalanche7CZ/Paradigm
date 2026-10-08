package eu.avalanche7.paradigm.modules.moderation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.avalanche7.paradigm.configs.ModerationConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.modules.audit.AuditSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.utils.TaskScheduler;

public final class PunishmentService {
    private final Services services;
    private final AuditService audit;
    private final ActivePunishmentCache cache = new ActivePunishmentCache();
    private final BanScreenFormatter banScreen;
    private final Object mutationLock = new Object();
    private final java.util.concurrent.ConcurrentHashMap<String, Object> jailLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastRefreshMs;
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile ScheduledFuture<?> refreshTask;

    public PunishmentService(Services services, AuditService audit) {
        this.services = services;
        this.audit = audit;
        this.banScreen = new BanScreenFormatter(services);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        refreshNow();
        TaskScheduler scheduler = services.getTaskScheduler();
        if (scheduler != null) {
            refreshTask = scheduler.scheduleAtFixedRate(this::refreshIfDue, 10L, 10L, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        started.set(false);
        ScheduledFuture<?> task = refreshTask;
        refreshTask = null;
        if (task != null) task.cancel(false);
    }

    public boolean isStarted() {
        return started.get();
    }

    public PunishmentRecord create(PunishmentType type, ServerScope scope, String subjectUuid, String subjectName,
                                   String ipAddress, String reason, String actorUuid, String actorName, Long expiresAtMs) {
        return create(type, scope, subjectUuid, subjectName, ipAddress, reason, actorUuid, actorName, expiresAtMs, Map.of());
    }

    public PunishmentRecord create(PunishmentType type, ServerScope scope, String subjectUuid, String subjectName,
                                   String ipAddress, String reason, String actorUuid, String actorName, Long expiresAtMs,
                                   Map<String, String> metadata) {
        return create(type, scope, subjectUuid, subjectName, ipAddress, reason, actorUuid, actorName, expiresAtMs,
                metadata, AuditSource.SYSTEM);
    }

    public PunishmentRecord create(PunishmentType type, ServerScope scope, String subjectUuid, String subjectName,
                                   String ipAddress, String reason, String actorUuid, String actorName, Long expiresAtMs,
                                   AuditSource source) {
        return create(type, scope, subjectUuid, subjectName, ipAddress, reason, actorUuid, actorName, expiresAtMs, Map.of(), source);
    }

    public PunishmentRecord create(PunishmentType type, ServerScope scope, String subjectUuid, String subjectName,
                                   String ipAddress, String reason, String actorUuid, String actorName, Long expiresAtMs,
                                   Map<String, String> metadata, AuditSource source) {
        if (type == PunishmentType.JAIL) throw new IllegalArgumentException("Jail requires an atomic state replacement.");
        PunishmentRecord record = newRecord(type, scope, subjectUuid, subjectName, ipAddress, reason,
                actorUuid, actorName, expiresAtMs, metadata, source);
        PunishmentRecord stored;
        synchronized (mutationLock) {
            stored = services.getStorageService().moderation().addPunishmentRecord(record);
            if (stored == null) throw new IllegalStateException("Punishment could not be stored.");
            cache.put(stored);
        }
        auditCreate(stored);
        publish(events -> events.punishmentCreated(stored));
        return stored;
    }

    public Optional<PunishmentRecord> createEscalation(String uuid, String name, String reason, String actorUuid,
                                                      String actorName, long expiresAtMs, Map<String, String> metadata,
                                                      AuditSource source, Map<String, Long> expected, long consumedAtMs) {
        PunishmentRecord candidate = newRecord(PunishmentType.BAN, ServerScope.GLOBAL, uuid, name, null, reason,
                actorUuid, actorName, expiresAtMs, metadata, source);
        Optional<PunishmentRecord> stored;
        synchronized (mutationLock) {
            stored = services.getStorageService().moderation().claimEscalation(candidate, expected, consumedAtMs);
            stored.ifPresent(cache::put);
        }
        stored.ifPresent(record -> {
            auditCreate(record);
            publish(events -> events.punishmentCreated(record));
        });
        return stored;
    }

    private PunishmentRecord newRecord(PunishmentType type, ServerScope scope, String subjectUuid, String subjectName,
                                       String ipAddress, String reason, String actorUuid, String actorName, Long expiresAtMs,
                                       Map<String, String> metadata, AuditSource source) {
        long now = System.currentTimeMillis();
        String canonicalIp = ipAddress != null && !ipAddress.isBlank() ? IpAddressUtil.canonicalize(ipAddress) : null;
        if (type == PunishmentType.IP_BAN && canonicalIp == null) throw new IllegalArgumentException("IP ban requires a valid address.");
        if ((type == PunishmentType.BAN || type == PunishmentType.MUTE || type == PunishmentType.JAIL)
                && !validUuid(subjectUuid)) throw new IllegalArgumentException("Player punishment requires a valid UUID.");
        var context = services.getStorageService().context();
        Map<String, String> details = new java.util.LinkedHashMap<>(metadata != null ? metadata : Map.of());
        details.put("auditSource", (source != null ? source : AuditSource.SYSTEM).name());
        return new PunishmentRecord(PunishmentIds.create(), type, scope, context.networkId(),
                scope == ServerScope.SERVER ? context.serverId() : null, clean(subjectUuid), clean(subjectName),
                canonicalIp != null ? IpAddressUtil.hash(canonicalIp) : null, canonicalIp, clean(reason), clean(actorUuid),
                clean(actorName), now, now, expiresAtMs, null, null, null, null, now, details);
    }

    public PunishmentRecord replaceJail(String subjectUuid, String name, StoredLocation location, String reason,
                                        String actorUuid, String actorName, Long expiresAtMs, AuditSource source) {
        if (location == null || !validUuid(subjectUuid)) throw new IllegalArgumentException("Jail requires a UUID and destination.");
        String uuid = java.util.UUID.fromString(subjectUuid).toString();
        if (expiresAtMs != null && expiresAtMs <= System.currentTimeMillis()) throw new IllegalArgumentException("Jail duration has already expired.");
        PunishmentRecord record;
        List<PunishmentRecord> superseded;
        synchronized (jailLock(uuid)) {
            var repository = services.getStorageService().moderation();
            StoredJailState previous = repository.getJailState(uuid).orElse(null);
            PlayerDataStore.StoredLocation original = onServer(() -> {
                IPlayer player = services.getPlatformAdapter().getPlayerByUuid(uuid);
                return player != null ? services.getPlatformAdapter().getPlayerLocation(player).orElse(null) : null;
            });
            boolean teleported = onServer(() -> {
                IPlayer player = services.getPlatformAdapter().getPlayerByUuid(uuid);
                return player == null || services.getPlatformAdapter().teleportPlayer(player, destination(location));
            });
            if (!teleported) {
                restorePosition(uuid, previous, original);
                throw new IllegalArgumentException("Could not teleport player to jail; previous jail was preserved.");
            }
            try {
                record = newRecord(PunishmentType.JAIL, ServerScope.SERVER, uuid, name, null, reason,
                        actorUuid, actorName, expiresAtMs, Map.of(), source);
                StoredJailState replacement = new StoredJailState(record.serverId(), uuid, name, reason, actorName,
                        location, record.createdAtMs(), expiresAtMs, record.punishmentId());
                synchronized (mutationLock) {
                    superseded = repository.replaceJail(record, previous, replacement);
                    for (PunishmentRecord old : superseded) cache.remove(old.punishmentId());
                    cache.put(record);
                }
            } catch (RuntimeException | Error failure) {
                try {
                    restorePosition(uuid, repository.getJailState(uuid).orElse(null), original);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
            for (PunishmentRecord old : superseded) {
                PunishmentRecord revoked = find(old.punishmentId()).orElseThrow();
                auditRevoke(revoked, actorUuid, actorName, source);
                publish(events -> events.punishmentRevoked(revoked));
            }
            auditCreate(record);
            publish(events -> events.punishmentCreated(record));
            return record;
        }
    }

    public boolean restoreJail(String subjectUuid) {
        return restoreJail(subjectUuid, () -> true);
    }

    public boolean restoreJail(String subjectUuid, java.util.function.BooleanSupplier enabled) {
        if (!validUuid(subjectUuid)) return false;
        String uuid = java.util.UUID.fromString(subjectUuid).toString();
        synchronized (jailLock(uuid)) {
            StoredJailState state = services.getStorageService().moderation().getJailState(uuid).orElse(null);
            if (!enabled.getAsBoolean()) return false;
            if (!validJail(state)) {
                if (state != null) {
                    if (services.getStorageService().moderation().clearJailState(state) && services.getLogger() != null) {
                        services.getLogger().warn("[Paradigm] Cleared invalid jail association for {}.", uuid);
                    }
                } else if (activeFor(uuid, null).stream()
                        .anyMatch(record -> record.type() == PunishmentType.JAIL && record.activeAt(System.currentTimeMillis()))
                        && services.getLogger() != null) {
                    services.getLogger().warn("[Paradigm] Active jail punishment for {} has no stored destination; restoration refused.", uuid);
                }
                return false;
            }
            return onServer(() -> {
                if (!enabled.getAsBoolean()) return false;
                IPlayer player = services.getPlatformAdapter().getPlayerByUuid(uuid);
                return player != null && services.getPlatformAdapter().teleportPlayer(player, destination(state.location()));
            });
        }
    }

    private boolean validJail(StoredJailState state) {
        if (state == null || state.location() == null || (state.expiresAtMs() != null && state.expiresAtMs() <= System.currentTimeMillis())) return false;
        return state.punishmentId() == null || find(state.punishmentId()).filter(record -> record.type() == PunishmentType.JAIL
                && state.uuid().equalsIgnoreCase(record.subjectUuid()) && record.activeAt(System.currentTimeMillis())
                && record.appliesTo(services.getStorageService().context().networkId(), services.getStorageService().context().serverId())).isPresent();
    }

    private void restorePosition(String uuid, StoredJailState previous, PlayerDataStore.StoredLocation original) {
        PlayerDataStore.StoredLocation restore = validJail(previous) ? destination(previous.location()) : original;
        if (restore == null) return;
        boolean restored = onServer(() -> {
            IPlayer player = services.getPlatformAdapter().getPlayerByUuid(uuid);
            return player == null || services.getPlatformAdapter().teleportPlayer(player, restore);
        });
        if (!restored) throw new IllegalStateException("Could not restore player's position after failed jail replacement.");
    }

    private <T> T onServer(java.util.function.Supplier<T> action) {
        java.util.concurrent.CompletableFuture<T> result = new java.util.concurrent.CompletableFuture<>();
        services.getPlatformAdapter().executeOnServerThread(() -> {
            if (result.isDone()) return;
            try {
                result.complete(action.get());
            } catch (RuntimeException | Error failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(15L, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            result.cancel(false);
            throw new IllegalStateException("Interrupted while waiting for the server thread.", failure);
        } catch (java.util.concurrent.TimeoutException failure) {
            result.cancel(false);
            throw new IllegalStateException("Server thread did not complete the jail operation.", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Server-thread jail operation failed.", failure);
        }
    }

    private static PlayerDataStore.StoredLocation destination(StoredLocation location) {
        return new PlayerDataStore.StoredLocation(location.worldId(), location.x(), location.y(), location.z(), location.yaw(), location.pitch());
    }

    private Object jailLock(String uuid) {
        return jailLocks.computeIfAbsent(uuid.toLowerCase(java.util.Locale.ROOT), ignored -> new Object());
    }

    public List<PunishmentRecord> activeRecords(String uuid, PunishmentType type) {
        return activeRecords(uuid, null, type);
    }

    public List<PunishmentRecord> activeRecords(String uuid, String address, PunishmentType type) {
        String hash = address != null && !address.isBlank() ? IpAddressUtil.hash(IpAddressUtil.canonicalize(address)) : null;
        return services.getStorageService().moderation().listActivePunishmentRecords(0L).stream()
                .filter(record -> record.type() == type && ((uuid != null && uuid.equalsIgnoreCase(record.subjectUuid()))
                        || (hash != null && hash.equals(record.subjectIpHash()))))
                .sorted(java.util.Comparator.comparing(PunishmentRecord::punishmentId)).toList();
    }

    public boolean revokeOfType(String id, PunishmentType type, String actorUuid, String actorName, String reason, AuditSource source) {
        PunishmentRecord record = find(id).orElse(null);
        return record != null && record.type() == type && revoke(id, actorUuid, actorName, reason, source);
    }

    public boolean revoke(String punishmentId, String actorUuid, String actorName, String reason) {
        return revoke(punishmentId, actorUuid, actorName, reason, AuditSource.SYSTEM);
    }

    public boolean revoke(String punishmentId, String actorUuid, String actorName, String reason, AuditSource source) {
        PunishmentRecord record = find(punishmentId).orElse(null);
        if (record == null) return false;
        if (record.type() == PunishmentType.JAIL) {
            synchronized (jailLock(record.subjectUuid())) {
                return revokeLocked(punishmentId, actorUuid, actorName, reason, source);
            }
        }
        return revokeLocked(punishmentId, actorUuid, actorName, reason, source);
    }

    private boolean revokeLocked(String punishmentId, String actorUuid, String actorName, String reason, AuditSource source) {
        PunishmentRecord existing;
        boolean changed;
        long now = System.currentTimeMillis();
        synchronized (mutationLock) {
            Optional<PunishmentRecord> current = find(punishmentId);
            if (current.isEmpty() || !current.get().activeAt(now)
                    || !current.get().appliesTo(services.getStorageService().context().networkId(), services.getStorageService().context().serverId())) return false;
            existing = current.get();
            changed = services.getStorageService().moderation().revokePunishmentRecord(punishmentId, now, clean(actorUuid), clean(actorName), clean(reason));
            if (changed) {
                cache.remove(punishmentId);
            }
        }
        if (changed) {
            auditRevoke(existing, actorUuid, actorName, source);
            PunishmentRecord revoked = find(punishmentId).orElseThrow();
            publish(events -> events.punishmentRevoked(revoked));
        }
        return changed;
    }

    public Optional<PunishmentRecord> find(String punishmentId) {
        if (!PunishmentIds.isValid(punishmentId)) return Optional.empty();
        return services.getStorageService().moderation().findPunishmentRecord(punishmentId);
    }

    public List<PunishmentRecord> history(String uuid, int page, int pageSize) {
        int limit = Math.max(1, Math.min(pageSize, 100));
        return services.getStorageService().moderation().listPunishmentRecords(uuid, Math.max(0, page - 1) * limit, limit);
    }

    public List<PunishmentRecord> activeFor(String uuid, String remoteAddress) {
        String hash = null;
        if (remoteAddress != null && !remoteAddress.isBlank()) {
            try { hash = IpAddressUtil.hash(IpAddressUtil.canonicalize(remoteAddress)); } catch (IllegalArgumentException ignored) { }
        }
        var context = services.getStorageService().context();
        return cache.activeFor(clean(uuid), hash, context.networkId(), context.serverId());
    }

    public Optional<PunishmentRecord> loginBlock(String uuid, String remoteAddress) {
        String hash = null;
        if (remoteAddress != null && !remoteAddress.isBlank()) {
            try { hash = IpAddressUtil.hash(IpAddressUtil.canonicalize(remoteAddress)); } catch (IllegalArgumentException ignored) { }
        }
        var context = services.getStorageService().context();
        return cache.loginBlock(clean(uuid), hash, context.networkId(), context.serverId());
    }

    public boolean enforcePlayer(IPlayer player) {
        if (player == null) return false;
        String address = services.getPlatformAdapter().getPlayerRemoteAddress(player);
        Optional<PunishmentRecord> blocked = loginBlock(player.getUUID(), address);
        if (blocked.isEmpty()) return false;
        return services.getPlatformAdapter().disconnectPlayer(player, banScreen.format(blocked.get()));
    }

    public BanScreenFormatter banScreen() { return banScreen; }
    public ActivePunishmentCache cache() { return cache; }

    public void refreshNow() {
        synchronized (mutationLock) {
            cache.replace(services.getStorageService().moderation().listActivePunishmentRecords(0L));
            lastRefreshMs = System.currentTimeMillis();
        }
    }

    public void refreshAsync() {
        if (!refreshRunning.compareAndSet(false, true)) return;
        services.getStorageService().runStorageAsync("moderation.cache-refresh", () -> {
            try {
                if (!started.get()) return;
                synchronized (mutationLock) {
                    cache.replace(services.getStorageService().moderation().listActivePunishmentRecords(0L));
                    lastRefreshMs = System.currentTimeMillis();
                }
            } finally {
                refreshRunning.set(false);
            }
        });
    }

    private void refreshIfDue() {
        int seconds = Math.max(10, ModerationConfigHandler.getConfig().cacheRefreshSeconds.value);
        if (System.currentTimeMillis() - lastRefreshMs >= seconds * 1000L) refreshAsync();
    }

    private void auditCreate(PunishmentRecord record) {
        if (audit == null) return;
        audit.record(record.actorUuid(), record.actorName(), auditSource(record), AuditActionType.MODERATION_ACTION,
                AuditResult.SUCCESS, "Punishment created.", auditDetails(record));
    }

    private void auditRevoke(PunishmentRecord record, String actorUuid, String actorName, AuditSource source) {
        if (audit == null) return;
        audit.record(actorUuid, actorName, source, AuditActionType.MODERATION_ACTION, AuditResult.SUCCESS,
                "Punishment revoked.", auditDetails(record));
    }

    private static Map<String, String> auditDetails(PunishmentRecord record) {
        return Map.of("punishmentId", record.punishmentId(), "type", record.type().name(),
                "targetUuid", safe(record.subjectUuid()), "targetName", safe(record.subjectName()), "scope", record.scope().name(),
                "ipSubject", record.subjectIpHash() != null ? IpAddressUtil.maskHash(record.subjectIpHash()) : "");
    }

    public static AuditSource auditSource(PunishmentRecord record) {
        try {
            return AuditSource.valueOf(record.metadata().getOrDefault("auditSource", "SYSTEM"));
        } catch (IllegalArgumentException ignored) {
            return AuditSource.SYSTEM;
        }
    }

    private void publish(java.util.function.Consumer<eu.avalanche7.paradigm.core.ParadigmEvents> action) {
        if (services == null) return;
        try {
            action.accept(services.getParadigmEvents());
        } catch (RuntimeException | LinkageError failure) {
            if (services.getLogger() != null) services.getLogger().warn("[Paradigm] Could not publish punishment event.", failure);
        }
    }

    private static String clean(String value) { String result = value != null ? value.trim() : null; return result == null || result.isBlank() ? null : result; }
    private static String safe(String value) { return value != null ? value : ""; }
    private static boolean validUuid(String value) {
        try { java.util.UUID.fromString(value); return true; }
        catch (Exception ignored) { return false; }
    }
}
