package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

public class MuteCommand extends AbstractModerationCommand {
    @Override
    public String getName() {
        return "Mute";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        registerMute();
        registerUnmute();
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        this.services = services;
        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        if (events == null) {
            return;
        }
        events.onPlayerChat(event -> {
            if (event == null || event.isCancelled() || event.getPlayer() == null) {
                return;
            }
            PunishmentRecord mute = services.getPunishmentService().activeFor(event.getPlayer().getUUID(), null).stream()
                    .filter(record -> record.type() == PunishmentType.MUTE).findFirst().orElse(null);
            if (mute == null) {
                return;
            }
            event.setCancelled(true);
            send(event.getPlayer(), "moderation.muted_chat_blocked", "You are muted. Remaining: {duration}. Reason: {reason}",
                    "{duration}", DurationParser.describeRemaining(mute.expiresAtMs() != null ? mute.expiresAtMs() : 0L),
                    "{reason}", mute.reason() != null && !mute.reason().isBlank() ? mute.reason() : reason(null));
        });
    }

    private void registerMute() {
        ICommandBuilder cmd = builder()
                .literal("mute")
                .requires(src -> allowed(src, "mute", PermissionsHandler.MUTE_PERMISSION, PermissionsHandler.MUTE_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> mute(ctx.getSource(), ctx.getPlayerArgument("player"), null))
                        .then(builder()
                                .argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> mute(ctx.getSource(), ctx.getPlayerArgument("player"), ctx.getStringArgument("reason")))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private void registerUnmute() {
        ICommandBuilder cmd = builder()
                .literal("unmute")
                .requires(src -> allowed(src, "unmute", PermissionsHandler.MUTE_PERMISSION, PermissionsHandler.MUTE_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> unmute(ctx.getSource(), ctx.getPlayerArgument("player"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int mute(eu.avalanche7.paradigm.platform.Interfaces.ICommandSource source, IPlayer target, String rawReason) {
        if (target == null) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        String reason = reason(rawReason);
        String targetUuid = target.getUUID();
        String targetName = target.getName();
        return StorageCommandSupport.runForSource(services, source, "moderation.mute", () -> {
            return services.getPunishmentService().create(PunishmentType.MUTE, ServerScope.SERVER, targetUuid, targetName,
                    null, reason, actorUuid(source), actorName(source), null);
        }, saved -> {
            send(source, "moderation.mute_ok", "Muted {player}. ID: {id}.", "{player}", targetName, "{id}", saved.punishmentId());
            IPlayer currentTarget = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
            if (currentTarget != null) {
                send(currentTarget, "moderation.muted", "You were muted. Reason: {reason}", "{reason}", reason);
            }
        }, "moderation.error_save");
    }

    private int unmute(eu.avalanche7.paradigm.platform.Interfaces.ICommandSource source, IPlayer target) {
        if (target == null) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        String targetUuid = target.getUUID();
        String targetName = target.getName();
        return StorageCommandSupport.runForSource(services, source, "moderation.unmute", () -> {
            java.util.List<PunishmentRecord> matches = services.getPunishmentService().activeFor(targetUuid, null).stream().filter(record -> record.type() == PunishmentType.MUTE).toList();
            return matches.size() == 1 && services.getPunishmentService().revoke(matches.get(0).punishmentId(), actorUuid(source), actorName(source), reason(null));
        }, changed -> {
            send(source, "moderation.unmute_ok", changed ? "Unmuted {player}." : "{player} was not muted.", "{player}", targetName);
            IPlayer currentTarget = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
            if (changed && currentTarget != null) {
                send(currentTarget, "moderation.unmuted", "You were unmuted.");
            }
        }, "moderation.error_save");
    }
}
