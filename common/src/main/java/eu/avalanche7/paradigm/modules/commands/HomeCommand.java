package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.utils.CommandCooldowns;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class HomeCommand implements ParadigmModule {
    private static final String HOME_LIMIT_PREFIX = "paradigm.home.limit.";
    private static final String HOME_LIMIT_UNLIMITED = "paradigm.home.limit.unlimited";
    private static final int HOME_LIMIT_SCAN_MAX = 256;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private Services services;
    private IPlatformAdapter platform;
    private final Map<String, List<String>> homeSuggestionCache = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "Home";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().homeCommandsEnable.value);
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
        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        if (events != null) {
            events.onPlayerDeath(event -> {
                IPlayer player = event.getPlayer();
                if (player == null) {
                    return;
                }
                services.getPlatformAdapter().getPlayerLocation(player)
                        .ifPresent(location -> services.getStorageService().runAsync(
                                "home.death_back_save",
                                () -> {
                                    services.getStorageService().players().setBackLocation(player.getUUID(), fromDataLocation(location));
                                    return null;
                                },
                                services.getTaskScheduler(),
                                ignored -> {},
                                ignored -> {}
                        ));
            });
        }
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
                .requires(source -> services.getCommandToggleStore().isEnabled("sethome")
                        && source.getPlayer() != null
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
                .requires(source -> source.getPlayer() != null)
                .executes(ctx -> canUseHome(ctx.getSource().getPlayer())
                        ? executeHome(ctx.getSource().getPlayer(), "home")
                        : 0)
                .then(platform.createCommandBuilder()
                        .literal("set")
                        .requires(source -> canSetHome(source.getPlayer()))
                        .executes(ctx -> executeSetHome(ctx.getSource().getPlayer(), "home"))
                        .then(platform.createCommandBuilder()
                                .argument("name", ICommandBuilder.ArgumentType.WORD)
                                .executes(ctx -> executeSetHome(ctx.getSource().getPlayer(), ctx.getStringArgument("name")))))
                .then(platform.createCommandBuilder()
                        .literal("delete")
                        .requires(source -> canDeleteHome(source.getPlayer()))
                        .then(platform.createCommandBuilder()
                                .argument("name", ICommandBuilder.ArgumentType.WORD)
                                .suggests((c, input) -> homeSuggestions(c.getSource().getPlayer()))
                                .executes(ctx -> executeDelHome(ctx.getSource().getPlayer(), ctx.getStringArgument("name")))))
                .then(platform.createCommandBuilder()
                        .literal("del")
                        .requires(source -> canDeleteHome(source.getPlayer()))
                        .then(platform.createCommandBuilder()
                                .argument("name", ICommandBuilder.ArgumentType.WORD)
                                .suggests((c, input) -> homeSuggestions(c.getSource().getPlayer()))
                                .executes(ctx -> executeDelHome(ctx.getSource().getPlayer(), ctx.getStringArgument("name")))))
                .then(platform.createCommandBuilder()
                        .literal("list")
                        .requires(source -> canListHomes(source.getPlayer()))
                        .executes(ctx -> executeHomes(ctx.getSource().getPlayer())))
                .then(platform.createCommandBuilder()
                        .literal("back")
                        .requires(source -> canBack(source.getPlayer()))
                        .executes(ctx -> executeBack(ctx.getSource().getPlayer())))
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((c, input) -> homeSuggestions(c.getSource().getPlayer()))
                        .executes(ctx -> canUseHome(ctx.getSource().getPlayer())
                                ? executeHome(ctx.getSource().getPlayer(), ctx.getStringArgument("name"))
                                : 0));
        platform.registerCommand(command);
    }

    private boolean canUseHome(IPlayer player) {
        return player != null
                && services.getCommandToggleStore().isEnabled("home")
                && services.getPermissionsHandler().hasPermission(player, PermissionsHandler.HOME_USE_PERMISSION, PermissionsHandler.HOME_USE_PERMISSION_LEVEL);
    }

    private boolean canSetHome(IPlayer player) {
        return player != null
                && services.getCommandToggleStore().isEnabled("sethome")
                && services.getPermissionsHandler().hasPermission(player, PermissionsHandler.HOME_SET_PERMISSION, PermissionsHandler.HOME_SET_PERMISSION_LEVEL);
    }

    private boolean canDeleteHome(IPlayer player) {
        return player != null
                && services.getCommandToggleStore().isEnabled("delhome")
                && services.getPermissionsHandler().hasPermission(player, PermissionsHandler.HOME_DEL_PERMISSION, PermissionsHandler.HOME_DEL_PERMISSION_LEVEL);
    }

    private boolean canListHomes(IPlayer player) {
        return player != null
                && services.getCommandToggleStore().isEnabled("homes")
                && services.getPermissionsHandler().hasPermission(player, PermissionsHandler.HOME_LIST_PERMISSION, PermissionsHandler.HOME_LIST_PERMISSION_LEVEL);
    }

    private boolean canBack(IPlayer player) {
        return player != null
                && services.getCommandToggleStore().isEnabled("back")
                && services.getPermissionsHandler().hasPermission(player, PermissionsHandler.BACK_PERMISSION, PermissionsHandler.BACK_PERMISSION_LEVEL);
    }

    private void registerDelHome() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("delhome")
                .requires(source -> services.getCommandToggleStore().isEnabled("delhome")
                        && source.getPlayer() != null
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
                .requires(source -> services.getCommandToggleStore().isEnabled("homes")
                        && source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.HOME_LIST_PERMISSION, PermissionsHandler.HOME_LIST_PERMISSION_LEVEL))
                .executes(ctx -> executeHomes(ctx.getSource().getPlayer()));
        platform.registerCommand(command);
    }

    private void registerBack() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("back")
                .requires(source -> services.getCommandToggleStore().isEnabled("back")
                        && source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.BACK_PERMISSION, PermissionsHandler.BACK_PERMISSION_LEVEL))
                .executes(ctx -> executeBack(ctx.getSource().getPlayer()));
        platform.registerCommand(command);
    }

    private int executeSetHome(IPlayer player, String homeName) {
        if (player == null) return 0;

        String normalized = normalizeInputName(homeName, "home");
        if (normalized == null) {
            send(player, "home.invalid_name", "Invalid home name. Use letters, numbers, _ or -.");
            return 0;
        }
        String homeKey = PlayerDataStore.normalizeHomeKey(normalized);
        if (homeKey == null) {
            send(player, "home.invalid_name", "Invalid home name. Use letters, numbers, _ or -.");
            return 0;
        }
        PlayerDataStore.StoredLocation location = platform.getPlayerLocation(player).orElse(null);
        if (location == null) {
            send(player, "home.location_unavailable", "Unable to read your location right now.");
            return 0;
        }

        int homeLimit = resolveHomeLimit(player);
        String playerUuid = player.getUUID();
        String playerName = player.getName();
        return StorageCommandSupport.runForPlayer(services, player, "home.sethome", () -> {
            boolean alreadyExists = services.getStorageService().players().getHome(playerUuid, homeKey).isPresent();
            List<StoredHome> homes = services.getStorageService().players().listHomes(playerUuid);
            int homeCount = homes.size();
            if (!alreadyExists && homeCount >= homeLimit) {
                return new SetHomeResult(false, homeCount, List.copyOf(homes), null);
            }
            long now = System.currentTimeMillis();
            services.getStorageService().players().upsertProfile(new StoredPlayerProfile(playerUuid, playerName, now, now));
            services.getStorageService().players().saveHome(new StoredHome(playerUuid, homeKey, fromDataLocation(location), now, now));
            List<StoredHome> updatedHomes = services.getStorageService().players().listHomes(playerUuid);
            return new SetHomeResult(true, updatedHomes.size(), List.copyOf(updatedHomes), null);
        }, (current, result) -> {
            updateHomeCache(playerUuid, result.homes());
            if (!result.saved()) {
                sendWithPlaceholders(current, "home.limit_reached", "You have reached your home limit ({count}/{limit}).",
                        "{count}", String.valueOf(result.count()),
                        "{limit}", String.valueOf(homeLimit));
                return;
            }
            send(current, "home.sethome_success", "Home {home} has been set.", "{home}", normalized);
        }, "home.error_save");
    }

    private int executeHome(IPlayer player, String homeName) {
        if (player == null) return 0;

        String normalized = normalizeInputName(homeName, "home");
        if (normalized == null) {
            send(player, "home.invalid_name", "Invalid home name. Use letters, numbers, _ or -.");
            return 0;
        }
        String homeKey = PlayerDataStore.normalizeHomeKey(normalized);
        String playerUuid = player.getUUID();
        return StorageCommandSupport.runForPlayer(services, player, "home.load", () ->
                homeKey != null ? services.getStorageService().players().getHome(playerUuid, homeKey).orElse(null) : null,
                (current, home) -> {
                    if (home == null || home.location() == null) {
                        send(current, "home.home_not_found", "Home {home} was not found.", "{home}", normalized);
                        return;
                    }
                    CommandCooldowns.run(services, current, "home", () -> teleportHome(current, normalized, toDataLocation(home.location())));
                },
                "home.error_load");
    }

    private int teleportHome(IPlayer player, String normalized, PlayerDataStore.StoredLocation destination) {
        PlayerDataStore.StoredLocation current = platform.getPlayerLocation(player).orElse(null);
        if (!platform.teleportPlayer(player, destination)) {
            send(player, "home.teleport_failed", "Teleport failed.");
            return 0;
        }
        if (current != null) {
            saveBackLocationAsync(player, current, "home.back_save");
        }

        send(player, "home.teleported", "Teleported to home {home}.", "{home}", normalized);
        return 1;
    }

    private int executeDelHome(IPlayer player, String homeName) {
        if (player == null) return 0;

        String normalized = normalizeInputName(homeName, "home");
        if (normalized == null) {
            send(player, "home.invalid_name", "Invalid home name. Use letters, numbers, _ or -.");
            return 0;
        }
        String homeKey = PlayerDataStore.normalizeHomeKey(normalized);
        String playerUuid = player.getUUID();
        return StorageCommandSupport.runForPlayer(services, player, "home.delete", () -> {
            boolean deleted = homeKey != null && services.getStorageService().players().deleteHome(playerUuid, homeKey);
            List<StoredHome> homes = services.getStorageService().players().listHomes(playerUuid);
            return new DeleteHomeResult(deleted, List.copyOf(homes));
        }, (current, result) -> {
            updateHomeCache(playerUuid, result.homes());
            if (!result.deleted()) {
                send(current, "home.delhome_missing", "Home {home} does not exist.", "{home}", normalized);
                return;
            }
            send(current, "home.delhome_success", "Home {home} has been deleted.", "{home}", normalized);
        }, "home.error_delete");
    }

    private int executeHomes(IPlayer player) {
        if (player == null) return 0;

        String playerUuid = player.getUUID();
        return StorageCommandSupport.runForPlayer(services, player, "home.list", () -> services.getStorageService().players().listHomes(playerUuid).stream()
                        .sorted(Comparator.comparing(StoredHome::name, String.CASE_INSENSITIVE_ORDER))
                        .toList(),
                (current, homes) -> {
                    updateHomeCache(playerUuid, homes);
                    sendHomes(current, homes);
                },
                "home.error_load");
    }

    private void sendHomes(IPlayer player, List<StoredHome> homes) {
        if (homes.isEmpty()) {
            send(player, "home.homes_empty", "You do not have any homes yet.");
            return;
        }

        send(player, "home.homes_header", "Homes ({count}):", "{count}", String.valueOf(homes.size()));
        for (StoredHome home : homes) {
            String homeName = home.name();
            String hoverText = "Click to teleport";
            if (home != null && home.location() != null) {
                StoredLocation loc = home.location();
                hoverText = String.format("%s | %.1f %.1f %.1f", loc.worldId(), loc.x(), loc.y(), loc.z());
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
    }

    private int executeBack(IPlayer player) {
        if (player == null) return 0;

        String playerUuid = player.getUUID();
        return StorageCommandSupport.runForPlayer(services, player, "home.back_load", () ->
                services.getStorageService().players().getBackLocation(playerUuid).orElse(null),
                (current, storedLast) -> {
                    if (storedLast == null) {
                        send(current, "home.back_missing", "No previous location saved for /back.");
                        return;
                    }
                    PlayerDataStore.StoredLocation last = toDataLocation(storedLast);
                    CommandCooldowns.run(services, current, "back", () -> teleportBack(current, last));
                },
                "home.error_load");
    }

    private int teleportBack(IPlayer player, PlayerDataStore.StoredLocation last) {
        PlayerDataStore.StoredLocation now = platform.getPlayerLocation(player).orElse(null);
        if (!platform.teleportPlayer(player, last)) {
            send(player, "home.teleport_failed", "Teleport failed.");
            return 0;
        }

        if (now != null) {
            saveBackLocationAsync(player, now, "home.back_swap_save");
        }

        send(player, "home.back_teleported", "Teleported back to your previous location.");
        return 1;
    }

    private List<String> homeSuggestions(IPlayer player) {
        if (player == null) return List.of();
        String uuid = player.getUUID();
        if (uuid == null || uuid.isBlank() || services == null || services.getStorageService() == null) {
            return List.of();
        }
        try {
            List<StoredHome> homes = services.getStorageService().players().listHomes(uuid);
            updateHomeCache(uuid, homes);
        } catch (Throwable t) {
            if (services.getDebugLogger() != null) {
                services.getDebugLogger().debugLog("HomeCommand: failed to refresh home suggestions: " + t);
            }
        }
        return homeSuggestionCache.getOrDefault(uuid, List.of());
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

    private String normalizeInputName(String value, String defaultName) {
        String normalized = value == null || value.isBlank() ? defaultName : value.trim();
        return SAFE_NAME.matcher(normalized).matches() ? normalized : null;
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
        return value.replace("<", "")
                .replace(">", "")
                .replace("&", "")
                .replace("'", "")
                .replace("\"", "")
                .replace("\n", " ");
    }

    private StoredLocation fromDataLocation(PlayerDataStore.StoredLocation location) {
        return new StoredLocation(location.getWorldId(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private PlayerDataStore.StoredLocation toDataLocation(StoredLocation location) {
        return new PlayerDataStore.StoredLocation(location.worldId(), location.x(), location.y(), location.z(), location.yaw(), location.pitch());
    }

    private void updateHomeCache(String uuid, List<StoredHome> homes) {
        if (uuid == null) {
            return;
        }
        homeSuggestionCache.put(uuid, homes.stream()
                .map(StoredHome::name)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
    }

    private void saveBackLocationAsync(IPlayer player, PlayerDataStore.StoredLocation location, String operation) {
        if (player == null || location == null) {
            return;
        }
        String uuid = player.getUUID();
        services.getStorageService().runAsync(operation, () -> {
            services.getStorageService().players().setBackLocation(uuid, fromDataLocation(location));
            return null;
        }, services.getTaskScheduler(), ignored -> {}, ignored -> {});
    }

    private record SetHomeResult(boolean saved, int count, List<StoredHome> homes, String message) {
    }

    private record DeleteHomeResult(boolean deleted, List<StoredHome> homes) {
    }
}
