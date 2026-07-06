package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

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
            StoredPunishment mute = ModerationRuntimeCache.getMute(event.getPlayer().getUUID());
            if (mute == null) {
                return;
            }
            event.setCancelled(true);
            send(event.getPlayer(), "moderation.muted_chat_blocked", "You are muted. Remaining: {duration}. Reason: {reason}",
                    "{duration}", DurationParser.describeRemaining(mute.expiresAtMs() != null ? mute.expiresAtMs() : 0L),
                    "{reason}", mute.reason() != null && !mute.reason().isBlank() ? mute.reason() : reason(null));
        });
        if (services.getStorageService() != null) {
            services.getStorageService().runAsync(
                    "moderation.mute_cache_load",
                    () -> services.getStorageService().moderation().listPunishments(),
                    services.getTaskScheduler(),
                    ModerationRuntimeCache::replaceMutes,
                    failure -> {
                    }
            );
        }
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
        StoredPunishment punishment = new StoredPunishment(
                0L,
                "mute",
                ServerScope.SERVER,
                null,
                targetUuid,
                targetName,
                reason,
                actorName(source),
                System.currentTimeMillis(),
                null,
                true
        );
        return StorageCommandSupport.runForSource(services, source, "moderation.mute", () -> {
            services.getStorageService().moderation().addPunishment(punishment);
            return punishment;
        }, saved -> {
            ModerationRuntimeCache.putMute(saved);
            send(source, "moderation.mute_ok", "Muted {player}.", "{player}", targetName);
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
            boolean changed = services.getStorageService().moderation().deactivateActivePunishments("mute", targetUuid, null);
            changed = services.getStorageService().moderation().deactivateActivePunishments("tempmute", targetUuid, null) || changed;
            return changed;
        }, changed -> {
            if (changed) {
                ModerationRuntimeCache.removeMute(targetUuid);
            }
            send(source, "moderation.unmute_ok", changed ? "Unmuted {player}." : "{player} was not muted.", "{player}", targetName);
            IPlayer currentTarget = services.getPlatformAdapter().getPlayerByUuid(targetUuid);
            if (changed && currentTarget != null) {
                send(currentTarget, "moderation.unmuted", "You were unmuted.");
            }
        }, "moderation.error_save");
    }
}
