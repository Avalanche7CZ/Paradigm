package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class TempBanCommand extends AbstractModerationCommand {
    @Override
    public String getName() {
        return "TempBan";
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        this.services = services;
        if (services != null && services.getTaskScheduler() != null) {
            services.getTaskScheduler().scheduleAtFixedRate(this::expireTempBans, 30L, 60L, TimeUnit.SECONDS);
        }
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = builder()
                .literal("tempban")
                .requires(src -> allowed(src, "tempban", PermissionsHandler.TEMPBAN_PERMISSION, PermissionsHandler.TEMPBAN_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.WORD)
                        .then(builder()
                                .argument("duration", ICommandBuilder.ArgumentType.WORD)
                                .suggests(List.of("30m", "1h", "1d", "7d"))
                                .executes(ctx -> tempBan(ctx.getSource(), ctx.getStringArgument("player"), ctx.getStringArgument("duration"), null))
                                .then(builder()
                                        .argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                        .executes(ctx -> tempBan(ctx.getSource(), ctx.getStringArgument("player"), ctx.getStringArgument("duration"), ctx.getStringArgument("reason"))))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int tempBan(ICommandSource source, String playerName, String durationRaw, String rawReason) {
        long durationMs = DurationParser.parseToMillis(durationRaw);
        if (durationMs <= 0L) {
            send(source, "moderation.duration_invalid", "Invalid duration. Use values like 30m, 2h, 7d.");
            return 0;
        }
        if (playerName == null || playerName.isBlank()) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        long expiresAt = System.currentTimeMillis() + durationMs;
        PlayerIdentity target = resolveIdentity(playerName);
        if (target == null) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        ScopeReason parsed = parseScopeReason(rawReason);
        return StorageCommandSupport.runForSource(services, source, "moderation.tempban", () -> {
            return services.getPunishmentService().create(PunishmentType.BAN, parsed.scope(), target.uuid(), target.name(), null,
                    parsed.reason(), actorUuid(source), actorName(source), expiresAt);
        }, punishment -> {
            if (target.online() != null) services.getPunishmentService().enforcePlayer(target.online());
            send(source, "moderation.punishment.created_temporary", "Banned {player} for {duration}. ID: {id}.",
                    "{player}", target.name(), "{duration}", DurationParser.describeRemaining(expiresAt), "{id}", punishment.punishmentId());
        }, "moderation.error_save");
    }

    private void expireTempBans() {
        if (services != null) services.getPunishmentService().refreshAsync();
    }
}
