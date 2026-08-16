package eu.avalanche7.paradigm.modules.moderation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.avalanche7.paradigm.configs.ModerationConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.utils.TaskScheduler;

public final class PunishmentService {
    private final Services services;
    private final AuditService audit;
    private final ActivePunishmentCache cache = new ActivePunishmentCache();
    private final BanScreenFormatter banScreen;
    private final Object mutationLock = new Object();
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
        long now = System.currentTimeMillis();
        String canonicalIp = ipAddress != null && !ipAddress.isBlank() ? IpAddressUtil.canonicalize(ipAddress) : null;
        if (type == PunishmentType.IP_BAN && canonicalIp == null) throw new IllegalArgumentException("IP ban requires a valid address.");
        if (type == PunishmentType.BAN && !validUuid(subjectUuid)) throw new IllegalArgumentException("Player ban requires a valid UUID.");
        var context = services.getStorageService().context();
        PunishmentRecord record = new PunishmentRecord(PunishmentIds.create(), type, scope, context.networkId(),
                scope == ServerScope.SERVER ? context.serverId() : null, clean(subjectUuid), clean(subjectName),
                canonicalIp != null ? IpAddressUtil.hash(canonicalIp) : null, canonicalIp, clean(reason), clean(actorUuid),
                clean(actorName), now, now, expiresAtMs, null, null, null, null, now, Map.of());
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

    public boolean revoke(String punishmentId, String actorUuid, String actorName, String reason) {
        PunishmentRecord existing;
        boolean changed;
        long now = System.currentTimeMillis();
        synchronized (mutationLock) {
            Optional<PunishmentRecord> current = find(punishmentId);
            if (current.isEmpty() || !current.get().activeAt(now)) return false;
            existing = current.get();
            changed = services.getStorageService().moderation().revokePunishmentRecord(punishmentId, now, clean(actorUuid), clean(actorName), clean(reason));
            if (changed) {
                cache.remove(punishmentId);
            }
        }
        if (changed) {
            auditRevoke(existing, actorName);
            PunishmentRecord revoked = existing.revoked(now, clean(actorUuid), clean(actorName), clean(reason));
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
        audit.dashboard(new DashboardPrincipal(record.actorUuid(), record.actorName(), false), AuditActionType.MODERATION_ACTION,
                AuditResult.SUCCESS, "Punishment created.", Map.of("punishmentId", record.punishmentId(), "type", record.type().name(),
                        "targetUuid", safe(record.subjectUuid()), "targetName", safe(record.subjectName()), "scope", record.scope().name(),
                        "ipSubject", record.subjectIpHash() != null ? IpAddressUtil.maskHash(record.subjectIpHash()) : ""));
    }

    private void auditRevoke(PunishmentRecord record, String actorName) {
        if (audit == null) return;
        audit.dashboard(new DashboardPrincipal(null, actorName, false), AuditActionType.MODERATION_ACTION, AuditResult.SUCCESS,
                "Punishment revoked.", Map.of("punishmentId", record.punishmentId(), "type", record.type().name(), "scope", record.scope().name()));
    }

    private void publish(java.util.function.Consumer<eu.avalanche7.paradigm.core.ParadigmEvents> action) {
        if (services == null) return;
        try {
            action.accept(services.getParadigmEvents());
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private static String clean(String value) { String result = value != null ? value.trim() : null; return result == null || result.isBlank() ? null : result; }
    private static String safe(String value) { return value != null ? value : ""; }
    private static boolean validUuid(String value) {
        try { java.util.UUID.fromString(value); return true; }
        catch (Exception ignored) { return false; }
    }
}
