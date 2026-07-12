package eu.avalanche7.paradigm.modules.moderation;

import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.configs.ModerationConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerScope;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PunishmentService {
    private final Services services;
    private final AuditService audit;
    private final ActivePunishmentCache cache = new ActivePunishmentCache();
    private final BanScreenFormatter banScreen;
    private volatile long lastRefreshMs;
    private final AtomicBoolean refreshRunning = new AtomicBoolean();

    public PunishmentService(Services services, AuditService audit) {
        this.services = services;
        this.audit = audit;
        this.banScreen = new BanScreenFormatter(services);
        refreshNow();
        if (services.getTaskScheduler() != null) {
            services.getTaskScheduler().scheduleAtFixedRate(this::refreshIfDue, 10L, 10L, TimeUnit.SECONDS);
        }
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
        PunishmentRecord stored = services.getStorageService().moderation().addPunishmentRecord(record);
        if (stored == null) throw new IllegalStateException("Punishment could not be stored.");
        cache.put(stored);
        auditCreate(stored);
        return stored;
    }

    public boolean revoke(String punishmentId, String actorUuid, String actorName, String reason) {
        Optional<PunishmentRecord> current = find(punishmentId);
        if (current.isEmpty() || !current.get().activeAt(System.currentTimeMillis())) return false;
        long now = System.currentTimeMillis();
        boolean changed = services.getStorageService().moderation().revokePunishmentRecord(punishmentId, now, clean(actorUuid), clean(actorName), clean(reason));
        if (changed) {
            cache.remove(punishmentId);
            auditRevoke(current.get(), actorName);
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
        cache.replace(services.getStorageService().moderation().listActivePunishmentRecords(0L));
        lastRefreshMs = System.currentTimeMillis();
    }

    public void refreshAsync() {
        if (!refreshRunning.compareAndSet(false, true)) return;
        CompletableFuture.supplyAsync(() -> services.getStorageService().moderation().listActivePunishmentRecords(0L))
                .thenAccept(records -> {
                    cache.replace(records);
                    lastRefreshMs = System.currentTimeMillis();
                }).whenComplete((ignored, error) -> refreshRunning.set(false));
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

    private static String clean(String value) { String result = value != null ? value.trim() : null; return result == null || result.isBlank() ? null : result; }
    private static String safe(String value) { return value != null ? value : ""; }
    private static boolean validUuid(String value) {
        try { java.util.UUID.fromString(value); return true; }
        catch (Exception ignored) { return false; }
    }
}
