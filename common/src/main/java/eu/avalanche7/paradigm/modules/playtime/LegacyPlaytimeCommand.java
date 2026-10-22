package eu.avalanche7.paradigm.modules.playtime;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;

public final class LegacyPlaytimeCommand {
    private final Services services;
    private final AtomicBoolean busy = new AtomicBoolean();

    public LegacyPlaytimeCommand(Services services) {
        this.services = services;
    }

    public void register() {
        var platform = services.getPlatformAdapter();
        platform.registerCommand(platform.createCommandBuilder().literal("paradigm")
                .then(platform.createCommandBuilder().literal("importplaytime").requires(this::allowed)
                        .executes(ctx -> run(ctx.getSource(), false))
                        .then(platform.createCommandBuilder().literal("confirm").requires(this::allowed)
                                .executes(ctx -> run(ctx.getSource(), true)))));
    }

    boolean allowed(ICommandSource source) {
        return source != null && (source.isConsole() || source.isPlayer() && source.hasPermissionLevel(2)
                && services.getPermissionsHandler().hasPermission(source.getPlayer(), ParadigmPermissions.STORAGE_MANAGE));
    }

    private void send(ICommandSource source, String message) {
        var platform = services.getPlatformAdapter();
        platform.sendSuccess(source, platform.createLiteralComponent(message), false);
    }

    private int run(ICommandSource source, boolean apply) {
        if (!allowed(source)) return 0;
        if (!busy.compareAndSet(false, true)) { send(source, "A legacy playtime scan/import is already running."); return 0; }
        var storage = services.getStorageService();
        if (storage == null || !storage.isSqlActive()) {
            busy.set(false);
            send(source, "Import unavailable: active SQL storage required (JSON/fallback is unsupported). Nothing imported.");
            return 0;
        }
        Path directory;
        try {
            directory = services.getPlatformAdapter().getPlayerStatisticsDirectory();
        } catch (RuntimeException failure) {
            failed(source, failure);
            return 0;
        }
        send(source, "Scanning world historical statistics...");
        storage.runAsync("legacy-playtime.scan", () -> {
            try {
                var localAtStart = new java.util.HashMap<String, Long>();
                storage.players().listServerPlaytime(storage.context().serverId()).forEach(value -> localAtStart.put(value.uuid(), value.playtimeMs()));
                return LegacyPlaytimeImport.scan(directory, localAtStart);
            }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
        }, services.getTaskScheduler(), scan -> {
            try {
                if (!allowed(source)) { busy.set(false); return; }
                var blocked = new HashSet<>(services.getPlaytimeService().legacyImportBlockedPlayers());
                services.getPlatformAdapter().getOnlinePlayers().forEach(p -> blocked.add(p.getUUID().toLowerCase(java.util.Locale.ROOT)));
                storage.runAsync("legacy-playtime.import", () -> {
                    if (!storage.isSqlActive()) throw new IllegalStateException("SQL storage is no longer active.");
                    return LegacyPlaytimeImport.process(scan, storage.players(), blocked, apply);
                }, services.getTaskScheduler(), report -> {
                    busy.set(false);
                    report.lines(apply).forEach(line -> send(source, line));
                }, failure -> failed(source, failure));
            } catch (RuntimeException failure) { failed(source, failure); }
        }, failure -> failed(source, failure));
        return 1;
    }

    private void failed(ICommandSource source, Throwable failure) {
        busy.set(false);
        while (failure.getCause() != null) failure = failure.getCause();
        if (services.getLogger() != null) services.getLogger().warn("Paradigm historical playtime import failed", failure);
        send(source, "Historical import stopped: " + failure.getMessage() + ". Inspect logs; committed UUIDs are safe to retry.");
    }
}
