package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

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
        String reason = reason(rawReason);
        String normalizedName = playerName.trim();
        StoredPunishment punishment = new StoredPunishment(
                0L,
                "tempban",
                ServerScope.GLOBAL,
                null,
                null,
                normalizedName,
                reason,
                actorName(source),
                System.currentTimeMillis(),
                expiresAt,
                true
        );
        return StorageCommandSupport.runForSource(services, source, "moderation.tempban", () -> {
            services.getStorageService().moderation().addPunishment(punishment);
            return true;
        }, ignored -> {
            services.getPlatformAdapter().executeCommandAsConsole("ban " + normalizedName + " " + reason + " (expires in " + DurationParser.describeRemaining(expiresAt) + ")");
            send(source, "moderation.tempban_ok", "Temp-banned {player} for {duration}.", "{player}", normalizedName, "{duration}", DurationParser.describeRemaining(expiresAt));
        }, "moderation.error_save");
    }

    private void expireTempBans() {
        if (services == null || services.getStorageService() == null) {
            return;
        }
        services.getStorageService().runAsync(
                "moderation.tempban_expire",
                () -> services.getStorageService().moderation().consumeExpiredPunishments(System.currentTimeMillis()),
                services.getTaskScheduler(),
                expired -> {
                    for (StoredPunishment entry : expired) {
                        if (entry != null && "tempban".equalsIgnoreCase(entry.type()) && entry.name() != null && !entry.name().isBlank()) {
                            services.getPlatformAdapter().executeCommandAsConsole("pardon " + entry.name());
                        }
                    }
                },
                failure -> {
            }
        );
    }
}
