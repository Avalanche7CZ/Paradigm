package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.util.List;

public class TempMuteCommand extends AbstractModerationCommand {
    @Override
    public String getName() {
        return "TempMute";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = builder()
                .literal("tempmute")
                .requires(src -> allowed(src, "tempmute", PermissionsHandler.TEMPMUTE_PERMISSION, PermissionsHandler.TEMPMUTE_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .then(builder()
                                .argument("duration", ICommandBuilder.ArgumentType.WORD)
                                .suggests(List.of("5m", "30m", "1h", "1d"))
                                .executes(ctx -> tempMute(ctx.getSource(), ctx.getPlayerArgument("player"), ctx.getStringArgument("duration"), null))
                                .then(builder()
                                        .argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                        .executes(ctx -> tempMute(ctx.getSource(), ctx.getPlayerArgument("player"), ctx.getStringArgument("duration"), ctx.getStringArgument("reason"))))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int tempMute(ICommandSource source, IPlayer target, String durationRaw, String rawReason) {
        if (target == null) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        long durationMs = DurationParser.parseToMillis(durationRaw);
        if (durationMs <= 0L) {
            send(source, "moderation.duration_invalid", "Invalid duration. Use values like 30m, 2h, 7d.");
            return 0;
        }
        long expiresAt = System.currentTimeMillis() + durationMs;
        String reason = reason(rawReason);
        String targetUuid = target.getUUID();
        String targetName = target.getName();
        StoredPunishment punishment = new StoredPunishment(
                0L,
                "tempmute",
                ServerScope.SERVER,
                null,
                targetUuid,
                targetName,
                reason,
                actorName(source),
                System.currentTimeMillis(),
                expiresAt,
                true
        );
        return StorageCommandSupport.runForSource(services, source, "moderation.tempmute", () -> {
            services.getStorageService().moderation().addPunishment(punishment);
            return punishment;
        }, saved -> {
            ModerationRuntimeCache.putMute(saved);
            send(source, "moderation.tempmute_ok", "Muted {player} for {duration}.",
                    "{player}", targetName, "{duration}", DurationParser.describeRemaining(expiresAt));
            IPlayer currentTarget = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
            if (currentTarget != null) {
                send(currentTarget, "moderation.tempmuted", "You were muted for {duration}. Reason: {reason}",
                        "{duration}", DurationParser.describeRemaining(expiresAt), "{reason}", reason);
            }
        }, "moderation.error_save");
    }
}
