package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.moderation.IpAddressUtil;
import eu.avalanche7.paradigm.modules.moderation.PunishmentIds;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

import java.util.List;

public final class IpBanCommand extends AbstractModerationCommand {
    @Override public String getName() { return "IPBan"; }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        registerCreate("ipban", false);
        registerCreate("tempipban", true);
        services.getPlatformAdapter().registerCommand(builder().literal("unipban")
                .requires(source -> allowed(source, "unipban", PermissionsHandler.IPBAN_PERMISSION, PermissionsHandler.BAN_PERMISSION_LEVEL))
                .then(builder().argument("target", ICommandBuilder.ArgumentType.WORD)
                        .executes(context -> revoke(context.getSource(), context.getStringArgument("target"), null))
                        .then(builder().argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(context -> revoke(context.getSource(), context.getStringArgument("target"), context.getStringArgument("reason"))))));
    }

    private void registerCreate(String literal, boolean temporary) {
        ICommandBuilder target = builder().argument("target", ICommandBuilder.ArgumentType.WORD);
        if (temporary) {
            target = target.then(builder().argument("duration", ICommandBuilder.ArgumentType.WORD).suggests(List.of("30m", "1h", "1d", "7d"))
                    .executes(context -> create(context.getSource(), context.getStringArgument("target"), context.getStringArgument("duration"), null))
                    .then(builder().argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                            .executes(context -> create(context.getSource(), context.getStringArgument("target"), context.getStringArgument("duration"), context.getStringArgument("reason")))));
        } else {
            target = target.executes(context -> create(context.getSource(), context.getStringArgument("target"), null, null))
                    .then(builder().argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                            .executes(context -> create(context.getSource(), context.getStringArgument("target"), null, context.getStringArgument("reason"))));
        }
        services.getPlatformAdapter().registerCommand(builder().literal(literal)
                .requires(source -> allowed(source, literal, PermissionsHandler.IPBAN_PERMISSION, PermissionsHandler.BAN_PERMISSION_LEVEL)).then(target));
    }

    private int create(ICommandSource source, String targetValue, String duration, String rawReason) {
        PlayerIdentity player = resolveIdentity(targetValue);
        String address = player != null && player.online() != null ? services.getPlatformAdapter().getPlayerRemoteAddress(player.online()) : targetValue;
        try { address = IpAddressUtil.canonicalize(address); }
        catch (IllegalArgumentException error) { send(source, "moderation.ip.invalid", "Invalid IPv4 or IPv6 address."); return 0; }
        Long expiresAt = null;
        if (duration != null) {
            long millis = DurationParser.parseToMillis(duration);
            if (millis <= 0L) { send(source, "moderation.duration_invalid", "Invalid duration."); return 0; }
            expiresAt = System.currentTimeMillis() + millis;
        }
        ScopeReason parsed = parseScopeReason(rawReason);
        String finalAddress = address;
        Long finalExpiresAt = expiresAt;
        return StorageCommandSupport.runForSource(services, source, "moderation.ipban", () -> services.getPunishmentService().create(
                PunishmentType.IP_BAN, parsed.scope(), player != null ? player.uuid() : null, player != null ? player.name() : null,
                finalAddress, parsed.reason(), actorUuid(source), actorName(source), finalExpiresAt), punishment -> {
            if (player != null && player.online() != null) services.getPunishmentService().enforcePlayer(player.online());
            send(source, "moderation.ip.created", "Created IP ban {id}. Subject: {subject}.", "{id}", punishment.punishmentId(), "{subject}", IpAddressUtil.mask(finalAddress));
        }, "moderation.error_save");
    }

    private int revoke(ICommandSource source, String target, String rawReason) {
        List<PunishmentRecord> matches;
        if (PunishmentIds.isValid(target)) {
            matches = services.getPunishmentService().find(target).filter(record -> record.type() == PunishmentType.IP_BAN && record.activeAt(System.currentTimeMillis())).stream().toList();
        } else {
            try { matches = services.getPunishmentService().activeFor(null, IpAddressUtil.canonicalize(target)).stream().filter(record -> record.type() == PunishmentType.IP_BAN).toList(); }
            catch (IllegalArgumentException error) { send(source, "moderation.ip.invalid", "Invalid IP address or punishment ID."); return 0; }
        }
        if (matches.size() != 1) {
            send(source, "moderation.punishment.ambiguous", "Use an exact punishment ID. Matching IDs: {ids}", "{ids}", matches.stream().map(PunishmentRecord::punishmentId).collect(java.util.stream.Collectors.joining(", ")));
            return 0;
        }
        boolean revoked = services.getPunishmentService().revoke(matches.get(0).punishmentId(), actorUuid(source), actorName(source), reason(rawReason));
        if (revoked) send(source, "moderation.punishment.revoked", "Revoked punishment {id}.", "{id}", matches.get(0).punishmentId());
        return revoked ? 1 : 0;
    }
}
