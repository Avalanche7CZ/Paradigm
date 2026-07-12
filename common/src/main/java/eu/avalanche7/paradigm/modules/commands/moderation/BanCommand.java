package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentIds;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

public class BanCommand extends AbstractModerationCommand {
    @Override
    public String getName() {
        return "Ban";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        registerBan();
        registerUnban("unban");
        registerUnban("pardon");
    }

    private void registerBan() {
        ICommandBuilder cmd = builder()
                .literal("ban")
                .requires(src -> allowed(src, "ban", PermissionsHandler.BAN_PERMISSION, PermissionsHandler.BAN_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.WORD)
                        .executes(ctx -> ban(ctx.getSource(), ctx.getStringArgument("player"), null))
                        .then(builder()
                                .argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> ban(ctx.getSource(), ctx.getStringArgument("player"), ctx.getStringArgument("reason")))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private void registerUnban(String literal) {
        ICommandBuilder cmd = builder()
                .literal(literal)
                .requires(src -> allowed(src, literal, PermissionsHandler.BAN_PERMISSION, PermissionsHandler.BAN_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.WORD)
                        .executes(ctx -> unban(ctx.getSource(), ctx.getStringArgument("player"), null))
                        .then(builder().argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> unban(ctx.getSource(), ctx.getStringArgument("player"), ctx.getStringArgument("reason")))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int ban(ICommandSource source, String playerName, String rawReason) {
        if (playerName == null || playerName.isBlank()) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        PlayerIdentity target = resolveIdentity(playerName);
        if (target == null) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        ScopeReason parsed = parseScopeReason(rawReason);
        return StorageCommandSupport.runForSource(services, source, "moderation.ban", () -> {
            return services.getPunishmentService().create(PunishmentType.BAN, parsed.scope(), target.uuid(), target.name(), null,
                    parsed.reason(), actorUuid(source), actorName(source), null);
        }, punishment -> {
            if (target.online() != null) services.getPunishmentService().enforcePlayer(target.online());
            send(source, "moderation.punishment.created", "Banned {player}. ID: {id}. Scope: {scope}.",
                    "{player}", target.name(), "{id}", punishment.punishmentId(), "{scope}", punishment.scope().name().toLowerCase(java.util.Locale.ROOT));
        }, "moderation.error_save");
    }

    private int unban(ICommandSource source, String playerName, String revokeReason) {
        if (playerName == null || playerName.isBlank()) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        PlayerIdentity target = PunishmentIds.isValid(playerName) ? null : resolveIdentity(playerName);
        if (target == null && !PunishmentIds.isValid(playerName)) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        return StorageCommandSupport.runForSource(services, source, "moderation.unban", () -> {
            java.util.List<PunishmentRecord> matches = PunishmentIds.isValid(playerName)
                    ? services.getPunishmentService().find(playerName).filter(record -> record.type() == PunishmentType.BAN
                            && record.activeAt(System.currentTimeMillis())).stream().toList()
                    : services.getPunishmentService().activeFor(target.uuid(), null).stream()
                            .filter(record -> record.type() == PunishmentType.BAN).toList();
            if (matches.size() != 1) return matches;
            services.getPunishmentService().revoke(matches.get(0).punishmentId(), actorUuid(source), actorName(source), reason(revokeReason));
            return matches;
        }, ignored -> {
            if (ignored.size() == 1) send(source, "moderation.punishment.revoked", "Revoked punishment {id}.", "{id}", ignored.get(0).punishmentId());
            else send(source, "moderation.punishment.ambiguous", "Use an exact punishment ID. Matching IDs: {ids}", "{ids}", ignored.stream().map(PunishmentRecord::punishmentId).collect(java.util.stream.Collectors.joining(", ")));
        }, "moderation.error_save");
    }
}
