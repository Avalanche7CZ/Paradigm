package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.util.List;

public class HomeCommand implements ParadigmModule {
    private static final String HOME_LIMIT_PREFIX = "paradigm.home.limit.";
    private static final String HOME_LIMIT_UNLIMITED = "paradigm.home.limit.unlimited";
    private static final int HOME_LIMIT_SCAN_MAX = 256;

    private Services services;
    private IPlatformAdapter platform;

    @Override
    public String getName() {
        return "Home";
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
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

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        registerSetHome();
        registerHome();
        registerDelHome();
        registerHomes();
        registerBack();
    }

    private void registerSetHome() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("sethome")
                .requires(source -> source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.HOME_SET_PERMISSION, PermissionsHandler.HOME_SET_PERMISSION_LEVEL))
                .executes(ctx -> executeSetHome(ctx.getSource().getPlayer(), "home"))
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .executes(ctx -> executeSetHome(ctx.getSource().getPlayer(), ctx.getStringArgument("name"))));
        platform.registerCommand(command);
    }

    private void registerHome() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("home")
                .requires(source -> source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.HOME_USE_PERMISSION, PermissionsHandler.HOME_USE_PERMISSION_LEVEL))
                .executes(ctx -> executeHome(ctx.getSource().getPlayer(), "home"))
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((c, input) -> homeSuggestions(c.getSource().getPlayer()))
                        .executes(ctx -> executeHome(ctx.getSource().getPlayer(), ctx.getStringArgument("name"))));
        platform.registerCommand(command);
    }

    private void registerDelHome() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("delhome")
                .requires(source -> source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.HOME_DEL_PERMISSION, PermissionsHandler.HOME_DEL_PERMISSION_LEVEL))
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((c, input) -> homeSuggestions(c.getSource().getPlayer()))
                        .executes(ctx -> executeDelHome(ctx.getSource().getPlayer(), ctx.getStringArgument("name"))));
        platform.registerCommand(command);
    }

    private void registerHomes() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("homes")
                .requires(source -> source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.HOME_LIST_PERMISSION, PermissionsHandler.HOME_LIST_PERMISSION_LEVEL))
                .executes(ctx -> executeHomes(ctx.getSource().getPlayer()));
        platform.registerCommand(command);
    }

    private void registerBack() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("back")
                .requires(source -> source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.BACK_PERMISSION, PermissionsHandler.BACK_PERMISSION_LEVEL))
                .executes(ctx -> executeBack(ctx.getSource().getPlayer()));
        platform.registerCommand(command);
    }

    private int executeSetHome(IPlayer player, String homeName) {
        if (player == null) return 0;

        String normalized = homeName == null || homeName.isBlank() ? "home" : homeName.trim();
        int homeLimit = resolveHomeLimit(player);
        boolean alreadyExists = services.getPlayerDataStore().getHome(player, normalized) != null;
        int homeCount = services.getPlayerDataStore().getHomeNames(player).size();
        if (!alreadyExists && homeCount >= homeLimit) {
            sendWithPlaceholders(player, "home.limit_reached", "You have reached your home limit ({count}/{limit}).",
                    "{count}", String.valueOf(homeCount),
                    "{limit}", String.valueOf(homeLimit));
            return 0;
        }

        // Ensure playerdata.json exists even if location capture fails.
        services.getPlayerDataStore().ensureExists(player);
        PlayerDataStore.StoredLocation location = platform.getPlayerLocation(player).orElse(null);
        if (location == null) {
            send(player, "home.location_unavailable", "Unable to read your location right now.");
            return 0;
        }

        services.getPlayerDataStore().setHome(player, normalized, location);
        send(player, "home.sethome_success", "Home {home} has been set.", "{home}", normalized);
        return 1;
    }

    private int executeHome(IPlayer player, String homeName) {
        if (player == null) return 0;

        String normalized = homeName == null || homeName.isBlank() ? "home" : homeName.trim();
        PlayerDataStore.HomeEntry home = services.getPlayerDataStore().getHome(player, normalized);
        if (home == null || home.getLocation() == null) {
            send(player, "home.home_not_found", "Home {home} was not found.", "{home}", normalized);
            return 0;
        }

        platform.getPlayerLocation(player).ifPresent(current -> services.getPlayerDataStore().setLastLocation(player, current));

        if (!platform.teleportPlayer(player, home.getLocation())) {
            send(player, "home.teleport_failed", "Teleport failed.");
            return 0;
        }

        send(player, "home.teleported", "Teleported to home {home}.", "{home}", normalized);
        return 1;
    }

    private int executeDelHome(IPlayer player, String homeName) {
        if (player == null) return 0;

        String normalized = homeName == null || homeName.isBlank() ? "home" : homeName.trim();
        boolean deleted = services.getPlayerDataStore().deleteHome(player, normalized);
        if (!deleted) {
            send(player, "home.delhome_missing", "Home {home} does not exist.", "{home}", normalized);
            return 0;
        }

        send(player, "home.delhome_success", "Home {home} has been deleted.", "{home}", normalized);
        return 1;
    }

    private int executeHomes(IPlayer player) {
        if (player == null) return 0;

        List<String> homes = services.getPlayerDataStore().getHomeNames(player);
        if (homes.isEmpty()) {
            send(player, "home.homes_empty", "You do not have any homes yet.");
            return 1;
        }

        send(player, "home.homes_header", "Homes ({count}):", "{count}", String.valueOf(homes.size()));
        for (String homeName : homes) {
            PlayerDataStore.HomeEntry home = services.getPlayerDataStore().getHome(player, homeName);
            String hoverText = "Click to teleport";
            if (home != null && home.getLocation() != null) {
                PlayerDataStore.StoredLocation loc = home.getLocation();
                hoverText = String.format("%s | %.1f %.1f %.1f", loc.getWorldId(), loc.getX(), loc.getY(), loc.getZ());
            }

            String safeHome = safe(homeName);
            IComponent line = platform.createEmptyComponent()
                    .append(platform.createComponentFromLiteral("[Home]").withColorHex("34D399").withFormatting("bold"))
                    .append(platform.createComponentFromLiteral(" "))
                    .append(platform.createComponentFromLiteral("• " + safeHome)
                            .withColorHex("93C5FD")
                            .onHoverText(safe(hoverText))
                            .onClickRunCommand("/home " + safeHome));
            platform.sendSystemMessage(player, line);
        }
        return 1;
    }

    private int executeBack(IPlayer player) {
        if (player == null) return 0;

        PlayerDataStore.StoredLocation last = services.getPlayerDataStore().getLastLocation(player);
        if (last == null) {
            send(player, "home.back_missing", "No previous location saved for /back.");
            return 0;
        }

        PlayerDataStore.StoredLocation now = platform.getPlayerLocation(player).orElse(null);
        if (!platform.teleportPlayer(player, last)) {
            send(player, "home.teleport_failed", "Teleport failed.");
            return 0;
        }

        if (now != null) {
            services.getPlayerDataStore().setLastLocation(player, now);
        }

        send(player, "home.back_teleported", "Teleported back to your previous location.");
        return 1;
    }

    private List<String> homeSuggestions(IPlayer player) {
        if (player == null) return List.of();
        return services.getPlayerDataStore().getHomeNames(player);
    }

    private int resolveHomeLimit(IPlayer player) {
        if (services.getPermissionsHandler().hasPermission(player, HOME_LIMIT_UNLIMITED)) {
            return Integer.MAX_VALUE;
        }

        for (int i = HOME_LIMIT_SCAN_MAX; i >= 1; i--) {
            if (services.getPermissionsHandler().hasPermission(player, HOME_LIMIT_PREFIX + i)) {
                return i;
            }
        }
        return 1;
    }

    private void send(IPlayer player, String key, String fallback) {
        sendWithPlaceholders(player, key, fallback);
    }

    private void send(IPlayer player, String key, String fallback, String placeholder, String value) {
        if (placeholder == null || value == null) {
            sendWithPlaceholders(player, key, fallback);
            return;
        }
        sendWithPlaceholders(player, key, fallback, placeholder, value);
    }

    private void sendWithPlaceholders(IPlayer player, String key, String fallback, String... placeholders) {
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        String decorated = "<color:#34D399><bold>[Home]</bold></color> <color:#E5E7EB>" + raw + "</color>";
        platform.sendSystemMessage(player, services.getMessageParser().parseMessage(decorated, player));
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "").replace("\n", " ");
    }
}


