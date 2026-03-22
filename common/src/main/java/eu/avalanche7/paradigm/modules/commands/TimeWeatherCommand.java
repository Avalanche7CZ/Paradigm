package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

public class TimeWeatherCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "TimeWeather";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().timeWeatherCommandsEnable.value);
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
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        registerTimeLiteral("day", "time set day");
        registerTimeLiteral("night", "time set night");

        registerWeatherLiteral("sun", "weather clear");
        registerWeatherLiteral("rain", "weather rain");
        registerWeatherLiteral("thunder", "weather thunder");
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private void registerTimeLiteral(String literal, String command) {
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal(literal)
                .requires(src -> services.getCommandToggleStore().isEnabled(literal)
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.TIME_PERMISSION, PermissionsHandler.TIME_PERMISSION_LEVEL))
                .executes(ctx -> executeTimeCommand(ctx.getSource().getPlayer(), command, literal));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private void registerWeatherLiteral(String literal, String command) {
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal(literal)
                .requires(src -> services.getCommandToggleStore().isEnabled(literal)
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.WEATHER_PERMISSION, PermissionsHandler.WEATHER_PERMISSION_LEVEL))
                .executes(ctx -> executeWeatherCommand(ctx.getSource().getPlayer(), command, literal));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int executeTimeCommand(IPlayer actor, String command, String state) {
        if (actor == null) {
            return 0;
        }
        services.getPlatformAdapter().executeCommandAsConsole(command);
        send(actor, "utility.time_set", "Time changed to {state}.", "{state}", state);
        return 1;
    }

    private int executeWeatherCommand(IPlayer actor, String command, String state) {
        if (actor == null) {
            return 0;
        }
        services.getPlatformAdapter().executeCommandAsConsole(command);
        send(actor, "utility.weather_set", "Weather changed to {state}.", "{state}", state);
        return 1;
    }

    private void send(IPlayer player, String key, String fallback, String... placeholders) {
        if (player == null) {
            return;
        }
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        String decorated = "<color:#22D3EE><bold>[Utility]</bold></color> <color:#E5E7EB>" + raw + "</color>";
        services.getPlatformAdapter().sendSystemMessage(player, services.getMessageParser().parseMessage(decorated, player));
    }
}

