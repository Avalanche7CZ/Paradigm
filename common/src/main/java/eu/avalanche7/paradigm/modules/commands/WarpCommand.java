package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.data.WarpStore;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredWarp;
import eu.avalanche7.paradigm.utils.CommandCooldowns;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class WarpCommand implements ParadigmModule {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private Services services;
    private IPlatformAdapter platform;
    private final Map<String, StoredWarp> warpCache = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "Warp";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().warpCommandsEnable.value);
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
        registerWarp();
        registerWarps();
        registerSetWarp();
        registerDelWarp();
        registerWarpInfo();
    }

    private void registerWarp() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("warp")
                .requires(source -> source.getPlayer() != null)
                .then(platform.createCommandBuilder()
                        .literal("set")
                        .requires(source -> canSetWarp(source.getPlayer()))
                        .then(platform.createCommandBuilder()
                                .argument("name", ICommandBuilder.ArgumentType.WORD)
                                .executes(ctx -> executeSetWarp(ctx.getSource().getPlayer(), ctx.getStringArgument("name")))))
                .then(platform.createCommandBuilder()
                        .literal("delete")
                        .requires(source -> canDeleteWarp(source.getPlayer()))
                        .then(platform.createCommandBuilder()
                                .argument("name", ICommandBuilder.ArgumentType.WORD)
                                .suggests((c, input) -> warpSuggestions(input))
                                .executes(ctx -> executeDelWarp(ctx.getSource().getPlayer(), ctx.getStringArgument("name")))))
                .then(platform.createCommandBuilder()
                        .literal("del")
                        .requires(source -> canDeleteWarp(source.getPlayer()))
                        .then(platform.createCommandBuilder()
                                .argument("name", ICommandBuilder.ArgumentType.WORD)
                                .suggests((c, input) -> warpSuggestions(input))
                                .executes(ctx -> executeDelWarp(ctx.getSource().getPlayer(), ctx.getStringArgument("name")))))
                .then(platform.createCommandBuilder()
                        .literal("list")
                        .requires(source -> canListWarps(source.getPlayer()))
                        .executes(ctx -> executeWarps(ctx.getSource().getPlayer())))
                .then(platform.createCommandBuilder()
                        .literal("info")
                        .requires(source -> canWarpInfo(source.getPlayer()))
                        .then(platform.createCommandBuilder()
                                .argument("name", ICommandBuilder.ArgumentType.WORD)
                                .suggests((c, input) -> warpSuggestions(input))
                                .executes(ctx -> executeWarpInfo(ctx.getSource().getPlayer(), ctx.getStringArgument("name")))))
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((c, input) -> warpSuggestions(input))
                        .executes(ctx -> canUseWarpCommand(ctx.getSource().getPlayer())
                                ? executeWarp(ctx.getSource().getPlayer(), ctx.getStringArgument("name"))
                                : 0));
        platform.registerCommand(command);
    }

    private boolean canUseWarpCommand(IPlayer player) {
        return player != null && services.getCommandToggleStore().isEnabled("warp");
    }

    private boolean canSetWarp(IPlayer player) {
        return player != null
                && services.getCommandToggleStore().isEnabled("setwarp")
                && services.getPermissionsHandler().hasPermission(player, PermissionsHandler.WARP_SET_PERMISSION, PermissionsHandler.WARP_SET_PERMISSION_LEVEL);
    }

    private boolean canDeleteWarp(IPlayer player) {
        return player != null
                && services.getCommandToggleStore().isEnabled("delwarp")
                && services.getPermissionsHandler().hasPermission(player, PermissionsHandler.WARP_DELETE_PERMISSION, PermissionsHandler.WARP_DELETE_PERMISSION_LEVEL);
    }

    private boolean canListWarps(IPlayer player) {
        return player != null
                && services.getCommandToggleStore().isEnabled("warps")
                && services.getPermissionsHandler().hasPermission(player, PermissionsHandler.WARP_LIST_PERMISSION, PermissionsHandler.WARP_LIST_PERMISSION_LEVEL);
    }

    private boolean canWarpInfo(IPlayer player) {
        return player != null
                && services.getCommandToggleStore().isEnabled("warpinfo")
                && services.getPermissionsHandler().hasPermission(player, PermissionsHandler.WARP_INFO_PERMISSION, PermissionsHandler.WARP_INFO_PERMISSION_LEVEL);
    }

    private void registerWarps() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("warps")
                .requires(source -> services.getCommandToggleStore().isEnabled("warps")
                        && source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.WARP_LIST_PERMISSION, PermissionsHandler.WARP_LIST_PERMISSION_LEVEL))
                .executes(ctx -> executeWarps(ctx.getSource().getPlayer()));
        platform.registerCommand(command);
    }

    private void registerSetWarp() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("setwarp")
                .requires(source -> services.getCommandToggleStore().isEnabled("setwarp")
                        && source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.WARP_SET_PERMISSION, PermissionsHandler.WARP_SET_PERMISSION_LEVEL))
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .executes(ctx -> executeSetWarp(ctx.getSource().getPlayer(), ctx.getStringArgument("name"))));
        platform.registerCommand(command);
    }

    private void registerDelWarp() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("delwarp")
                .requires(source -> services.getCommandToggleStore().isEnabled("delwarp")
                        && source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.WARP_DELETE_PERMISSION, PermissionsHandler.WARP_DELETE_PERMISSION_LEVEL))
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((c, input) -> warpSuggestions(input))
                        .executes(ctx -> executeDelWarp(ctx.getSource().getPlayer(), ctx.getStringArgument("name"))));
        platform.registerCommand(command);
    }

    private void registerWarpInfo() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("warpinfo")
                .requires(source -> services.getCommandToggleStore().isEnabled("warpinfo")
                        && source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.WARP_INFO_PERMISSION, PermissionsHandler.WARP_INFO_PERMISSION_LEVEL))
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((c, input) -> warpSuggestions(input))
                        .executes(ctx -> executeWarpInfo(ctx.getSource().getPlayer(), ctx.getStringArgument("name"))));
        platform.registerCommand(command);
    }

    private int executeSetWarp(IPlayer player, String warpNameInput) {
        if (player == null) return 0;

        String warpName = normalizeInputName(warpNameInput);
        if (warpName == null) {
            send(player, "warp.invalid_name", "Invalid warp name.");
            return 0;
        }

        PlayerDataStore.StoredLocation location = platform.getPlayerLocation(player).orElse(null);
        if (location == null) {
            send(player, "warp.location_unavailable", "Unable to read your location right now.");
            return 0;
        }

        String permission = WarpStore.defaultPermissionFor(WarpStore.normalizeWarpKey(warpName));
        long now = System.currentTimeMillis();
        StoredWarp warp = new StoredWarp(
                warpName,
                fromDataLocation(location),
                permission,
                "",
                player.getName(),
                now,
                now
        );
        return StorageCommandSupport.runForPlayer(services, player, "warp.set", () -> {
            services.getStorageService().warps().saveWarp(warp);
            return services.getStorageService().warps().listWarps();
        }, (current, warps) -> {
            updateWarpCache(warps);
            send(current, "warp.set_success", "Warp {warp} has been set.", "{warp}", warpName);
        }, "warp.error_save");
    }

    private int executeWarp(IPlayer player, String warpNameInput) {
        if (player == null) return 0;

        String warpName = normalizeInputName(warpNameInput);
        if (warpName == null) {
            send(player, "warp.invalid_name", "Invalid warp name.");
            return 0;
        }

        return StorageCommandSupport.runForPlayer(services, player, "warp.load", () ->
                services.getStorageService().warps().getWarp(warpName).orElse(null),
                (current, warp) -> {
                    if (warp == null || warp.location() == null) {
                        send(current, "warp.not_found", "Warp {warp} was not found.", "{warp}", warpName);
                        return;
                    }
                    if (!canUseWarp(current, warp, warpName)) {
                        send(current, "warp.no_permission", "You do not have permission to use warp {warp}.", "{warp}", warpName);
                        return;
                    }
                    CommandCooldowns.run(services, current, "warp", () -> teleportWarp(current, warp));
                },
                "warp.error_load");
    }

    private int teleportWarp(IPlayer player, StoredWarp warp) {
        PlayerDataStore.StoredLocation previous = platform.getPlayerLocation(player).orElse(null);
        if (!platform.teleportPlayer(player, toDataLocation(warp.location()))) {
            send(player, "warp.teleport_failed", "Teleport failed.");
            return 0;
        }
        if (previous != null) {
            saveBackLocationAsync(player, previous, "warp.back_save");
        }

        send(player, "warp.teleported", "Teleported to warp {warp}.", "{warp}", warp.name());
        return 1;
    }

    private int executeWarps(IPlayer player) {
        if (player == null) return 0;

        return StorageCommandSupport.runForPlayer(services, player, "warp.list", () -> services.getStorageService().warps().listWarps(),
                (current, warps) -> {
                    updateWarpCache(warps);
                    sendWarps(current, warps);
                },
                "warp.error_load");
    }

    private void sendWarps(IPlayer player, List<StoredWarp> warps) {
        if (warps.isEmpty()) {
            send(player, "warp.list_empty", "No warps are set.");
            return;
        }

        List<StoredWarp> visibleWarps = warps.stream()
                .filter(warp -> warp != null && warp.location() != null)
                .filter(warp -> canUseWarp(player, warp, warp.name()))
                .toList();

        if (visibleWarps.isEmpty()) {
            send(player, "warp.list_empty", "No warps are set.");
            return;
        }

        send(player, "warp.list_header", "Warps ({count}):", "{count}", String.valueOf(visibleWarps.size()));
        for (StoredWarp warp : visibleWarps) {
            String warpName = warp.name();

            String node = warp.permission() != null ? warp.permission() : WarpStore.defaultPermissionFor(WarpStore.normalizeWarpKey(warpName));
            String hover = String.format("%s | %.1f %.1f %.1f | %s",
                    warp.location().worldId(),
                    warp.location().x(),
                    warp.location().y(),
                    warp.location().z(),
                    node != null ? node : "-");

            String safeWarp = safe(warpName);
            String commandWarp = WarpStore.normalizeWarpKey(warpName);
            if (commandWarp == null) {
                continue;
            }
            IComponent line = platform.createEmptyComponent()
                    .append(platform.createComponentFromLiteral("[Warp]").withColorHex("F59E0B").withFormatting("bold"))
                    .append(platform.createComponentFromLiteral(" "))
                    .append(platform.createComponentFromLiteral("• " + safeWarp)
                            .withColorHex("93C5FD")
                            .onHoverText(safe(hover))
                            .onClickRunCommand("/warp " + safe(commandWarp)));
            platform.sendSystemMessage(player, line);
        }
    }

    private int executeDelWarp(IPlayer player, String warpNameInput) {
        if (player == null) return 0;

        String warpName = normalizeInputName(warpNameInput);
        if (warpName == null) {
            send(player, "warp.invalid_name", "Invalid warp name.");
            return 0;
        }

        return StorageCommandSupport.runForPlayer(services, player, "warp.delete", () -> {
            boolean deleted = services.getStorageService().warps().deleteWarp(warpName);
            return new DeleteWarpResult(deleted, services.getStorageService().warps().listWarps());
        }, (current, result) -> {
            updateWarpCache(result.warps());
            if (!result.deleted()) {
                send(current, "warp.not_found", "Warp {warp} was not found.", "{warp}", warpName);
                return;
            }
            send(current, "warp.delete_success", "Warp {warp} has been deleted.", "{warp}", warpName);
        }, "warp.error_delete");
    }

    private int executeWarpInfo(IPlayer player, String warpNameInput) {
        if (player == null) return 0;

        String warpName = normalizeInputName(warpNameInput);
        if (warpName == null) {
            send(player, "warp.invalid_name", "Invalid warp name.");
            return 0;
        }

        return StorageCommandSupport.runForPlayer(services, player, "warp.info", () ->
                services.getStorageService().warps().getWarp(warpName).orElse(null),
                (current, warp) -> sendWarpInfo(current, warpName, warp),
                "warp.error_load");
    }

    private void sendWarpInfo(IPlayer player, String warpName, StoredWarp warp) {
        if (warp == null || warp.location() == null) {
            send(player, "warp.not_found", "Warp {warp} was not found.", "{warp}", warpName);
            return;
        }

        String permission = warp.permission() != null ? warp.permission() : WarpStore.defaultPermissionFor(WarpStore.normalizeWarpKey(warpName));
        String details = "<color:#F59E0B><bold>[Warp]</bold></color> "
                + "<color:#E5E7EB>" + safe(warp.name()) + "</color>"
                + " <color:#6B7280>|</color> <color:#93C5FD>" + safe(warp.location().worldId()) + "</color>"
                + " <color:#6B7280>|</color> <color:#E5E7EB>"
                + String.format(Locale.US, "%.1f %.1f %.1f", warp.location().x(), warp.location().y(), warp.location().z())
                + "</color>"
                + " <color:#6B7280>|</color> <color:#A3E635>" + safe(permission) + "</color>";
        platform.sendSystemMessage(player, services.getMessageParser().parseMessage(details, player));
    }

    private boolean canUseWarp(IPlayer player, StoredWarp warp, String warpNameInput) {
        String specific = warp.permission();
        if (specific == null || specific.isBlank()) {
            specific = WarpStore.defaultPermissionFor(WarpStore.normalizeWarpKey(warpNameInput));
        }

        return services.getPermissionsHandler().hasPermission(player, PermissionsHandler.WARP_WILDCARD_PERMISSION, PermissionsHandler.WARP_WILDCARD_PERMISSION_LEVEL)
                || (specific != null && services.getPermissionsHandler().hasPermission(player, specific));
    }

    private List<String> warpSuggestions(String input) {
        String q = input != null ? input.trim().toLowerCase(Locale.ROOT) : "";
        if (services != null && services.getStorageService() != null) {
            try {
                updateWarpCache(services.getStorageService().warps().listWarps());
            } catch (Throwable t) {
                if (services.getDebugLogger() != null) {
                    services.getDebugLogger().debugLog("WarpCommand: failed to refresh warp suggestions: " + t);
                }
            }
        }
        return warpCache.values().stream()
                .map(StoredWarp::name)
                .filter(name -> q.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(q))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private String normalizeInputName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (!SAFE_NAME.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    private void send(IPlayer player, String key, String fallback) {
        send(player, key, fallback, null, null);
    }

    private void send(IPlayer player, String key, String fallback, String placeholder, String value) {
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        if (placeholder != null && value != null) {
            raw = raw.replace(placeholder, value);
        }
        String decorated = "<color:#F59E0B><bold>[Warp]</bold></color> <color:#E5E7EB>" + raw + "</color>";
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

    private void updateWarpCache(List<StoredWarp> warps) {
        warpCache.clear();
        for (StoredWarp warp : warps) {
            if (warp != null && warp.name() != null) {
                String key = WarpStore.normalizeWarpKey(warp.name());
                if (key != null) {
                    warpCache.put(key, warp);
                }
            }
        }
    }

    private record DeleteWarpResult(boolean deleted, List<StoredWarp> warps) {
    }
}
