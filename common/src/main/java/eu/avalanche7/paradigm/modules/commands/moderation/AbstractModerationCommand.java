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
