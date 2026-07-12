package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.commands.shared.CommandMessages;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

import java.util.List;

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
        PunishmentRecord record = services.getPunishmentService().find(id).orElse(null);
        if (record == null) { send(services, source, "moderation.punishment.not_found", "Punishment not found."); return 0; }
        record = record.withoutSensitiveIp();
        send(services, source, "moderation.punishment.detail", "{id} {type} {status} | {player} | {scope} | {reason}",
                "{id}", record.punishmentId(), "{type}", record.type().name(), "{status}", record.status(System.currentTimeMillis()).name(),
                "{player}", record.subjectName() != null ? record.subjectName() : "IP subject", "{scope}", record.scope().name().toLowerCase(java.util.Locale.ROOT),
                "{reason}", record.reason() != null ? record.reason() : "");
        return 1;
    }

    private static int revoke(ICommandSource source, String id, String reason, Services services) {
        String actorUuid = source.getPlayer() != null ? source.getPlayer().getUUID() : null;
        boolean changed = services.getPunishmentService().revoke(id, actorUuid, source.getSourceName(), reason);
        send(services, source, changed ? "moderation.punishment.revoked" : "moderation.punishment.not_found",
                changed ? "Revoked punishment {id}." : "Punishment was not found or is not active.", "{id}", id);
        return changed ? 1 : 0;
    }

    private static int history(ICommandSource source, String target, Services services) {
        String uuid = target;
        String name = target;
        var profile = services.getStorageService().players().listProfiles().stream()
                .filter(item -> target.equalsIgnoreCase(item.uuid()) || target.equalsIgnoreCase(item.name())).findFirst().orElse(null);
        if (profile != null) { uuid = profile.uuid(); name = profile.name(); }
        List<PunishmentRecord> history = services.getPunishmentService().history(uuid, 1, 10);
        if (history.isEmpty()) { send(services, source, "moderation.history.empty", "No punishment history for {player}.", "{player}", name); return 1; }
        send(services, source, "moderation.history.header", "Punishment history for {player}:", "{player}", name);
        for (PunishmentRecord record : history) {
            send(services, source, "moderation.history.row", "{id} {type} {status} - {reason}", "{id}", record.punishmentId(),
                    "{type}", record.type().name(), "{status}", record.status(System.currentTimeMillis()).name(), "{reason}", record.reason() != null ? record.reason() : "");
        }
        return 1;
    }

    private static boolean allowed(ICommandSource source, Services services) {
        if (source == null) return false;
        if (source.isConsole()) return true;
        return source.getPlayer() != null && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.BAN_PERMISSION, PermissionsHandler.BAN_PERMISSION_LEVEL);
    }

    private static void send(Services services, ICommandSource source, String key, String fallback, String... placeholders) {
        CommandMessages.source(services, source, "Moderation", key, fallback, placeholders);
    }
}
