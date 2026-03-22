package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.data.WarpStore;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.util.List;
import java.util.Locale;

public class WarpCommand implements ParadigmModule {
    private Services services;
    private IPlatformAdapter platform;
    private WarpStore warpStore;

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
        this.warpStore = new WarpStore(services.getLogger(), services.getDebugLogger(), platform.getConfig());
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
        warpStore.upsert(warpName, location, permission);
        send(player, "warp.set_success", "Warp {warp} has been set.", "{warp}", warpName);
        return 1;
    }

    private int executeWarp(IPlayer player, String warpNameInput) {
        if (player == null) return 0;

        String warpName = normalizeInputName(warpNameInput);
        if (warpName == null) {
            send(player, "warp.invalid_name", "Invalid warp name.");
            return 0;
        }

        WarpStore.WarpEntry warp = warpStore.get(warpName);
        if (warp == null || warp.getLocation() == null) {
            send(player, "warp.not_found", "Warp {warp} was not found.", "{warp}", warpName);
            return 0;
        }

        if (!canUseWarp(player, warp, warpName)) {
            send(player, "warp.no_permission", "You do not have permission to use warp {warp}.", "{warp}", warpName);
            return 0;
        }

        platform.getPlayerLocation(player).ifPresent(loc -> services.getPlayerDataStore().setLastLocation(player, loc));
        if (!platform.teleportPlayer(player, warp.getLocation())) {
            send(player, "warp.teleport_failed", "Teleport failed.");
            return 0;
        }

        send(player, "warp.teleported", "Teleported to warp {warp}.", "{warp}", warp.getName());
        return 1;
    }

    private int executeWarps(IPlayer player) {
        if (player == null) return 0;

        List<String> names = warpStore.listNames();
        if (names.isEmpty()) {
            send(player, "warp.list_empty", "No warps are set.");
            return 1;
        }

        List<WarpStore.WarpEntry> visibleWarps = names.stream()
                .map(warpStore::get)
                .filter(warp -> warp != null && warp.getLocation() != null)
                .filter(warp -> canUseWarp(player, warp, warp.getName()))
                .toList();

        if (visibleWarps.isEmpty()) {
            send(player, "warp.list_empty", "No warps are set.");
            return 1;
        }

        send(player, "warp.list_header", "Warps ({count}):", "{count}", String.valueOf(visibleWarps.size()));
        for (WarpStore.WarpEntry warp : visibleWarps) {
            String warpName = warp.getName();

            String node = warp.getPermission() != null ? warp.getPermission() : WarpStore.defaultPermissionFor(WarpStore.normalizeWarpKey(warpName));
            String hover = String.format("%s | %.1f %.1f %.1f | %s",
                    warp.getLocation().getWorldId(),
                    warp.getLocation().getX(),
                    warp.getLocation().getY(),
                    warp.getLocation().getZ(),
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
        return 1;
    }

    private int executeDelWarp(IPlayer player, String warpNameInput) {
        if (player == null) return 0;

        String warpName = normalizeInputName(warpNameInput);
        if (warpName == null) {
            send(player, "warp.invalid_name", "Invalid warp name.");
            return 0;
        }

        if (!warpStore.delete(warpName)) {
            send(player, "warp.not_found", "Warp {warp} was not found.", "{warp}", warpName);
            return 0;
        }

        send(player, "warp.delete_success", "Warp {warp} has been deleted.", "{warp}", warpName);
        return 1;
    }

    private int executeWarpInfo(IPlayer player, String warpNameInput) {
        if (player == null) return 0;

        String warpName = normalizeInputName(warpNameInput);
        if (warpName == null) {
            send(player, "warp.invalid_name", "Invalid warp name.");
            return 0;
        }

        WarpStore.WarpEntry warp = warpStore.get(warpName);
        if (warp == null || warp.getLocation() == null) {
            send(player, "warp.not_found", "Warp {warp} was not found.", "{warp}", warpName);
            return 0;
        }

        String permission = warp.getPermission() != null ? warp.getPermission() : WarpStore.defaultPermissionFor(WarpStore.normalizeWarpKey(warpName));
        String details = "<color:#F59E0B><bold>[Warp]</bold></color> "
                + "<color:#E5E7EB>" + safe(warp.getName()) + "</color>"
                + " <color:#6B7280>|</color> <color:#93C5FD>" + safe(warp.getLocation().getWorldId()) + "</color>"
                + " <color:#6B7280>|</color> <color:#E5E7EB>"
                + String.format(Locale.US, "%.1f %.1f %.1f", warp.getLocation().getX(), warp.getLocation().getY(), warp.getLocation().getZ())
                + "</color>"
                + " <color:#6B7280>|</color> <color:#A3E635>" + safe(permission) + "</color>";
        platform.sendSystemMessage(player, services.getMessageParser().parseMessage(details, player));
        return 1;
    }

    private boolean canUseWarp(IPlayer player, WarpStore.WarpEntry warp, String warpNameInput) {
        String specific = warp.getPermission();
        if (specific == null || specific.isBlank()) {
            specific = WarpStore.defaultPermissionFor(WarpStore.normalizeWarpKey(warpNameInput));
        }

        return services.getPermissionsHandler().hasPermission(player, PermissionsHandler.WARP_USE_PERMISSION, PermissionsHandler.WARP_USE_PERMISSION_LEVEL)
                || services.getPermissionsHandler().hasPermission(player, PermissionsHandler.WARP_WILDCARD_PERMISSION, PermissionsHandler.WARP_WILDCARD_PERMISSION_LEVEL)
                || (specific != null && services.getPermissionsHandler().hasPermission(player, specific));
    }

    private List<String> warpSuggestions(String input) {
        String q = input != null ? input.trim().toLowerCase(Locale.ROOT) : "";
        return warpStore.listNames().stream()
                .filter(name -> q.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(q))
                .toList();
    }

    private String normalizeInputName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 32) {
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
        return value.replace("'", "").replace("\n", " ");
    }
}

