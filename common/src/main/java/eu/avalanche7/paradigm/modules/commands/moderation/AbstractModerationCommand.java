package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.audit.AuditSource;
import eu.avalanche7.paradigm.modules.commands.shared.CommandMessages;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public abstract class AbstractModerationCommand implements ParadigmModule {
    protected Services services;

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().moderationCommandsEnable.value);
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
    }

    @Override
    public void onServerStarting(Object event, Services services) {
    }

    @Override
    public void onEnable(Services services) {
    }

    @Override
    public void onDisable(Services services) {
    }

    @Override
    public void onServerStopping(Object event, Services services) {
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    protected ICommandBuilder builder() {
        return services.getPlatformAdapter().createCommandBuilder();
    }

    protected boolean allowed(ICommandSource source, String root, PermissionDefinition permission) {
        return permission != null && allowed(source, root, permission.node(), permission.fallbackLevel());
    }

    protected boolean allowed(ICommandSource source, String root, String permission, int level) {
        return services != null && services.getCommandAccess().allowsSource(source, root, permission, level);
    }

    protected String actorName(ICommandSource source) {
        if (source == null) {
            return "unknown";
        }
        String name = source.getSourceName();
        return name != null && !name.isBlank() ? name : "console";
    }

    protected String actorUuid(ICommandSource source) {
        IPlayer player = source != null ? source.getPlayer() : null;
        return player != null ? player.getUUID() : null;
    }

    protected PlayerIdentity resolveIdentity(String input) {
        if (input == null || input.isBlank()) return null;
        IPlayer online = services.getPlatformAdapter().getPlayerByName(input);
        if (online == null) online = services.getPlatformAdapter().getPlayerByUuid(input);
        if (online != null) return new PlayerIdentity(online.getUUID(), online.getName(), online);
        String value = input.trim();
        return services.getStorageService().players().listProfiles().stream()
                .filter(profile -> value.equalsIgnoreCase(profile.uuid()) || value.equalsIgnoreCase(profile.name()))
                .findFirst().map(profile -> new PlayerIdentity(profile.uuid(), profile.name(), null)).orElse(null);
    }

    protected ScopeReason parseScopeReason(String raw) {
        String value = raw != null ? raw.trim() : "";
        eu.avalanche7.paradigm.storage.identity.ServerScope scope = eu.avalanche7.paradigm.storage.identity.ServerScope.GLOBAL;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|\\s)--scope\\s+(network|global|server)(?=\\s|$)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value);
        if (matcher.find()) {
            scope = "server".equalsIgnoreCase(matcher.group(1)) ? eu.avalanche7.paradigm.storage.identity.ServerScope.SERVER : eu.avalanche7.paradigm.storage.identity.ServerScope.GLOBAL;
            value = (value.substring(0, matcher.start()) + " " + value.substring(matcher.end())).trim().replaceAll("\\s+", " ");
        }
        return new ScopeReason(scope, reason(value));
    }

    protected record PlayerIdentity(String uuid, String name, IPlayer online) { }
    protected record ScopeReason(eu.avalanche7.paradigm.storage.identity.ServerScope scope, String reason) { }

    protected int revokeTarget(ICommandSource source, String input, eu.avalanche7.paradigm.modules.moderation.PunishmentType type,
                               String operation, String successKey, String successFallback, String emptyFallback) {
        String value = input != null ? input.trim() : "";
        boolean exactId = eu.avalanche7.paradigm.modules.moderation.PunishmentIds.isValid(value);
        if (value.isBlank() || (value.toUpperCase(java.util.Locale.ROOT).startsWith("P-") && !exactId)) {
            send(source, "moderation.punishment.not_found", "Punishment was not found or is not active.");
            return 0;
        }
        IPlayer online = exactId ? null : services.getPlatformAdapter().getPlayerByName(value);
        if (online == null && !exactId) online = services.getPlatformAdapter().getPlayerByUuid(value);
        PlayerIdentity captured = online != null ? new PlayerIdentity(online.getUUID(), online.getName(), online) : null;
        return eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport.runForSource(services, source, operation, () -> {
            var punishments = services.getPunishmentService();
            PlayerIdentity identity = captured;
            if (!exactId && identity == null) {
                identity = services.getStorageService().players().listProfiles().stream()
                        .filter(profile -> value.equalsIgnoreCase(profile.uuid()) || value.equalsIgnoreCase(profile.name()))
                        .findFirst().map(profile -> new PlayerIdentity(profile.uuid(), profile.name(), null)).orElse(null);
            }
            java.util.List<eu.avalanche7.paradigm.modules.moderation.PunishmentRecord> matches = exactId
                    ? punishments.find(value).filter(record -> record.type() == type && record.activeAt(System.currentTimeMillis())
                            && record.appliesTo(services.getStorageService().context().networkId(), services.getStorageService().context().serverId()))
                            .stream().toList()
                    : identity != null ? punishments.activeRecords(identity.uuid(), type) : java.util.List.of();
            boolean changed = matches.size() == 1 && punishments.revokeOfType(matches.get(0).punishmentId(), type,
                    actorUuid(source), actorName(source), reason(null), AuditSource.COMMAND);
            return new RevocationResult(identity, matches, changed);
        }, result -> {
            if (result.matches().isEmpty()) {
                if (exactId) send(source, "moderation.punishment.not_found", "Punishment was not found or is not active.");
                else if (result.identity() == null) send(source, "moderation.player_not_found", "Player not found.");
                else send(source, "moderation.punishment.inactive", emptyFallback, "{player}", result.identity().name());
                return;
            }
            if (result.matches().size() > 1) {
                send(source, "moderation.punishment.ambiguous", "Use an exact punishment ID. Matching IDs: {ids}",
                        "{ids}", result.matches().stream().map(eu.avalanche7.paradigm.modules.moderation.PunishmentRecord::punishmentId)
                                .collect(java.util.stream.Collectors.joining(", ")));
                return;
            }
            if (!result.changed()) {
                send(source, "moderation.punishment.not_found", "Punishment was not found or is not active.");
                return;
            }
            var record = result.matches().get(0);
            if (exactId) {
                send(source, "moderation.punishment.revoked", "Revoked punishment {id}.", "{id}", record.punishmentId());
            } else {
                send(source, successKey, successFallback, "{player}", record.subjectName() != null ? record.subjectName() : record.subjectUuid(),
                        "{id}", record.punishmentId());
            }
            IPlayer current = services.getPlatformAdapter().getPlayerByUuid(record.subjectUuid());
            if (current != null && services.getPunishmentService().activeFor(record.subjectUuid(), null).stream()
                    .noneMatch(active -> active.type() == type)) {
                send(current, type == eu.avalanche7.paradigm.modules.moderation.PunishmentType.MUTE
                        ? "moderation.unmuted" : "moderation.unjailed",
                        type == eu.avalanche7.paradigm.modules.moderation.PunishmentType.MUTE ? "You were unmuted." : "You were unjailed.");
            }
        }, "moderation.error_save");
    }

    private record RevocationResult(PlayerIdentity identity,
                                    java.util.List<eu.avalanche7.paradigm.modules.moderation.PunishmentRecord> matches,
                                    boolean changed) {
    }

    protected String reason(String raw) {
        if (raw != null && !raw.isBlank()) {
            return raw.trim();
        }
        if (services != null && services.getLang() != null) {
            String translated = services.getLang().getTranslation("moderation.no_reason");
            if (translated != null && !translated.equals("moderation.no_reason")) {
                return translated;
            }
        }
        return "No reason provided.";
    }

    protected void send(ICommandSource source, String key, String fallback, String... placeholders) {
        CommandMessages.source(services, source, "Moderation", key, fallback, placeholders);
    }

    protected void send(IPlayer player, String key, String fallback, String... placeholders) {
        CommandMessages.send(services, player, "Moderation", key, fallback, placeholders);
    }
}
