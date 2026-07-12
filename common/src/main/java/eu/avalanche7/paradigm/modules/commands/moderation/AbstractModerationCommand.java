package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.CommandMessages;
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

    protected boolean allowed(ICommandSource source, String root, String permission, int level) {
        if (services == null || services.getCommandToggleStore() == null || !services.getCommandToggleStore().isEnabled(root)) {
            return false;
        }
        if (source == null) {
            return false;
        }
        if (source.isConsole()) {
            return true;
        }
        IPlayer player = source.getPlayer();
        return player != null && services.getPermissionsHandler().hasPermission(player, permission, level);
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
