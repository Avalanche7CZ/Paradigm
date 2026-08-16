package eu.avalanche7.paradigm.modules.commands.moderation;

import java.util.List;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.CommandMessages;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class PunishmentCommands {
    private PunishmentCommands() { }

    public static ICommandBuilder register(ICommandBuilder paradigm, IPlatformAdapter platform, Services services) {
        ICommandBuilder branch = platform.createCommandBuilder().literal("punishment")
                .requires(source -> allowed(source, services))
                .then(platform.createCommandBuilder().literal("info")
                        .then(platform.createCommandBuilder().argument("id", ICommandBuilder.ArgumentType.WORD)
                                .executes(context -> info(context.getSource(), context.getStringArgument("id"), services))))
                .then(platform.createCommandBuilder().literal("revoke")
                        .then(platform.createCommandBuilder().argument("id", ICommandBuilder.ArgumentType.WORD)
                                .executes(context -> revoke(context.getSource(), context.getStringArgument("id"), null, services))
                                .then(platform.createCommandBuilder().argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                        .executes(context -> revoke(context.getSource(), context.getStringArgument("id"), context.getStringArgument("reason"), services)))))
                .then(platform.createCommandBuilder().literal("history")
                        .then(platform.createCommandBuilder().argument("player", ICommandBuilder.ArgumentType.WORD)
                                .executes(context -> history(context.getSource(), context.getStringArgument("player"), services))));
        platform.registerCommand(platform.createCommandBuilder().literal("history").requires(source -> allowed(source, services))
                .then(platform.createCommandBuilder().argument("player", ICommandBuilder.ArgumentType.WORD)
                        .executes(context -> history(context.getSource(), context.getStringArgument("player"), services))));
        return paradigm.then(branch);
    }

    private static int info(ICommandSource source, String id, Services services) {
        return StorageCommandSupport.runForSource(services, source, "moderation.punishment.info",
                () -> services.getPunishmentService().find(id).orElse(null),
                record -> {
                    if (record == null) {
                        send(services, source, "moderation.punishment.not_found", "Punishment not found.");
                        return;
                    }
                    PunishmentRecord safeRecord = record.withoutSensitiveIp();
                    send(services, source, "moderation.punishment.detail", "{id} {type} {status} | {player} | {scope} | {reason}",
                            "{id}", safeRecord.punishmentId(), "{type}", safeRecord.type().name(),
                            "{status}", safeRecord.status(System.currentTimeMillis()).name(),
                            "{player}", safeRecord.subjectName() != null ? safeRecord.subjectName() : "IP subject",
                            "{scope}", safeRecord.scope().name().toLowerCase(java.util.Locale.ROOT),
                            "{reason}", safeRecord.reason() != null ? safeRecord.reason() : "");
                },
                "moderation.error_load");
    }

    private static int revoke(ICommandSource source, String id, String reason, Services services) {
        String actorUuid = source.getPlayer() != null ? source.getPlayer().getUUID() : null;
        String actorName = source.getSourceName();
        return StorageCommandSupport.runForSource(services, source, "moderation.punishment.revoke",
                () -> services.getPunishmentService().revoke(id, actorUuid, actorName, reason),
                changed -> send(services, source,
                        changed ? "moderation.punishment.revoked" : "moderation.punishment.not_found",
                        changed ? "Revoked punishment {id}." : "Punishment was not found or is not active.",
                        "{id}", id),
                "moderation.error_save");
    }

    private static int history(ICommandSource source, String target, Services services) {
        IPlayer online = services.getPlatformAdapter().getPlayerByName(target);
        if (online == null) {
            online = services.getPlatformAdapter().getPlayerByUuid(target);
        }
        String onlineUuid = online != null ? online.getUUID() : null;
        String onlineName = online != null ? online.getName() : null;

        return StorageCommandSupport.runForSource(services, source, "moderation.punishment.history", () -> {
            String uuid = onlineUuid != null && !onlineUuid.isBlank() ? onlineUuid : target;
            String name = onlineName != null && !onlineName.isBlank() ? onlineName : target;
            var profile = services.getStorageService().players().listProfiles().stream()
                    .filter(item -> target.equalsIgnoreCase(item.uuid()) || target.equalsIgnoreCase(item.name()))
                    .findFirst()
                    .orElse(null);
            if (profile != null) {
                uuid = profile.uuid();
                name = profile.name();
            }
            return new HistoryResult(name, services.getPunishmentService().history(uuid, 1, 10));
        }, result -> {
            if (result.history().isEmpty()) {
                send(services, source, "moderation.history.empty", "No punishment history for {player}.",
                        "{player}", result.name());
                return;
            }
            send(services, source, "moderation.history.header", "Punishment history for {player}:",
                    "{player}", result.name());
            for (PunishmentRecord record : result.history()) {
                send(services, source, "moderation.history.row", "{id} {type} {status} - {reason}",
                        "{id}", record.punishmentId(),
                        "{type}", record.type().name(),
                        "{status}", record.status(System.currentTimeMillis()).name(),
                        "{reason}", record.reason() != null ? record.reason() : "");
            }
        }, "moderation.error_load");
    }

    private static boolean allowed(ICommandSource source, Services services) {
        if (source == null) return false;
        if (source.isConsole()) return true;
        return source.getPlayer() != null
                && services.getPermissionsHandler().hasPermission(source.getPlayer(), ParadigmPermissions.BAN);
    }

    private static void send(Services services, ICommandSource source, String key, String fallback, String... placeholders) {
        CommandMessages.source(services, source, "Moderation", key, fallback, placeholders);
    }

    private record HistoryResult(String name, List<PunishmentRecord> history) {
    }
}
