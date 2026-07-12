package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

public class WarnCommand extends AbstractModerationCommand {
    @Override
    public String getName() {
        return "Warn";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = builder()
                .literal("warn")
                .requires(src -> allowed(src, "warn", PermissionsHandler.WARN_PERMISSION, PermissionsHandler.WARN_PERMISSION_LEVEL))
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
        return StorageCommandSupport.runForSource(services, source, "moderation.warn", () -> {
            return services.getPunishmentService().create(PunishmentType.WARN, ServerScope.GLOBAL, targetUuid, targetName,
                    null, reason, actorUuid(source), actorName(source), null);
        }, warning -> {
            send(source, "moderation.warn_ok", "Warned {player}. ID: {id}.", "{player}", targetName, "{id}", warning.punishmentId());
            IPlayer currentTarget = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
            if (currentTarget != null) {
                send(currentTarget, "moderation.warned", "You were warned. Reason: {reason}", "{reason}", reason);
            }
        }, "moderation.error_save");
    }
}
