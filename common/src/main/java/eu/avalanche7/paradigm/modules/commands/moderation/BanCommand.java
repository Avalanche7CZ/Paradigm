package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

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
                        .executes(ctx -> unban(ctx.getSource(), ctx.getStringArgument("player"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int ban(ICommandSource source, String playerName, String rawReason) {
        if (playerName == null || playerName.isBlank()) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        String reason = reason(rawReason);
        String normalizedName = playerName.trim();
        StoredPunishment punishment = new StoredPunishment(
                0L,
                "ban",
                ServerScope.GLOBAL,
                null,
                null,
                normalizedName,
                reason,
                actorName(source),
                System.currentTimeMillis(),
                null,
                true
        );
        return StorageCommandSupport.runForSource(services, source, "moderation.ban", () -> {
            services.getStorageService().moderation().addPunishment(punishment);
            return true;
        }, ignored -> {
            services.getPlatformAdapter().executeCommandAsConsole("ban " + normalizedName + " " + reason);
            send(source, "moderation.ban_ok", "Banned {player}.", "{player}", normalizedName);
        }, "moderation.error_save");
    }

    private int unban(ICommandSource source, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        String normalizedName = playerName.trim();
        return StorageCommandSupport.runForSource(services, source, "moderation.unban", () -> {
            services.getStorageService().moderation().deactivateActivePunishments("tempban", null, normalizedName);
            services.getStorageService().moderation().deactivateActivePunishments("ban", null, normalizedName);
            return true;
        }, ignored -> {
            services.getPlatformAdapter().executeCommandAsConsole("pardon " + normalizedName);
            send(source, "moderation.unban_ok", "Unbanned {player}.", "{player}", normalizedName);
        }, "moderation.error_save");
    }
}
