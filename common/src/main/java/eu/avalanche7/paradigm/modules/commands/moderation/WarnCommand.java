package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.modules.moderation.WarnEscalationService;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.utils.DurationFormatter;

public class WarnCommand extends AbstractModerationCommand {
    private record WarnOutcome(PunishmentRecord warning, WarnEscalationService.Result escalation) {
    }

    @Override
    public String getName() {
        return "Warn";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = builder()
                .literal("warn")
                .requires(src -> allowed(src, "warn", ParadigmPermissions.WARN))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .then(builder()
                                .argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> warn(ctx.getSource(), ctx.getPlayerArgument("player"), ctx.getStringArgument("reason")))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int warn(ICommandSource source, IPlayer target, String rawReason) {
        if (target == null) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        String reason = reason(rawReason);
        String targetUuid = target.getUUID();
        String targetName = target.getName();
        String actorUuid = actorUuid(source);
        String actorName = actorName(source);
        return StorageCommandSupport.runForSource(services, source, "moderation.warn", () -> {
            PunishmentRecord warning = services.getPunishmentService().create(PunishmentType.WARN, ServerScope.GLOBAL,
                    targetUuid, targetName, null, reason, actorUuid, actorName, null);
            WarnEscalationService.Result escalation = services.getWarnEscalationService()
                    .evaluate(targetUuid, targetName, warning, actorUuid, actorName);
            return new WarnOutcome(warning, escalation);
        }, outcome -> {
            send(source, "moderation.warn_ok", "Warned {player}. ID: {id}.",
                    "{player}", targetName, "{id}", outcome.warning().punishmentId());
            IPlayer currentTarget = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
            if (currentTarget != null) {
                send(currentTarget, "moderation.warned", "You were warned. Reason: {reason}", "{reason}", reason);
            }
            announceEscalation(source, targetUuid, targetName, outcome.escalation());
        }, "moderation.error_save");
    }

    private void announceEscalation(ICommandSource source, String targetUuid, String targetName,
                                    WarnEscalationService.Result escalation) {
        if (escalation == null || escalation.punishment() == null) {
            return;
        }
        String duration = DurationFormatter.compact(escalation.rule().banMs());
        send(source, "moderation.escalation.applied",
                "Warn escalation: {player} reached {count} warnings in {window} and was banned for {duration}. ID: {id}.",
                "{player}", targetName,
                "{count}", Integer.toString(escalation.warningCount()),
                "{window}", escalation.rule().window(),
                "{duration}", duration,
                "{id}", escalation.punishment().punishmentId());

        IPlayer currentTarget = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
        if (currentTarget != null) {
            send(currentTarget, "moderation.escalation.notified",
                    "You reached {count} warnings and were banned for {duration}. Reason: {reason}",
                    "{count}", Integer.toString(escalation.warningCount()),
                    "{duration}", duration,
                    "{reason}", escalation.punishment().reason() != null ? escalation.punishment().reason() : "");
            services.getPunishmentService().enforcePlayer(currentTarget);
        }
    }
}
