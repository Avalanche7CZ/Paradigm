package eu.avalanche7.paradigm.modules.permissions;

import eu.avalanche7.paradigm.configs.CMConfig;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextResolver;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionDataStore;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PermissionsHandler {
    private final Logger logger;
    private final CMConfig cmConfig;
    private final DebugLogger debugLogger;
    private final IPlatformAdapter platform;
    private final StorageService storageService;
    private final Set<UUID> discoveryWarmedUsers = ConcurrentHashMap.newKeySet();
    private final PermissionAPI internalPermissionApi;
    private final PermissionNodeRegistry permissionNodeRegistry;

    public static final String MENTION_EVERYONE_PERMISSION = "paradigm.mention.everyone";
    public static final String MENTION_PLAYER_PERMISSION = "paradigm.mention.player";
    public static final String STAFF_CHAT_PERMISSION = "paradigm.staff";
    public static final String RESTART_MANAGE_PERMISSION = "paradigm.restart.manage";
    public static final String BROADCAST_PERMISSION = "paradigm.broadcast";
    public static final String GROUPCHAT_PERMISSION = "paradigm.groupchat";
    public static final String RELOAD_PERMISSION = "paradigm.reload";
    public static final String COMMAND_TOGGLE_PERMISSION = "paradigm.command.toggle";
    public static final String STORAGE_MANAGE_PERMISSION = "paradigm.storage.manage";
    public static final String GROUP_MANAGE_PERMISSION = "paradigm.group.manage";
    public static final String EDITOR_PERMISSION = "paradigm.editor";
    public static final String PRIVATE_MESSAGE_PERMISSION = "paradigm.msg";
    public static final String PRIVATE_REPLY_PERMISSION = "paradigm.reply";
    public static final String SOCIALSPY_PERMISSION = "paradigm.socialspy";
    public static final String SPAWN_PERMISSION = "paradigm.spawn";
    public static final String SETSPAWN_PERMISSION = "paradigm.setspawn";
    public static final String SEEN_PERMISSION = "paradigm.seen";
    public static final String IGNORE_PERMISSION = "paradigm.ignore";
    public static final String GAMEMODE_PERMISSION = "paradigm.gamemode";
    public static final String GAMEMODE_OTHERS_PERMISSION = "paradigm.gamemode.others";
    public static final String FLY_PERMISSION = "paradigm.fly";
    public static final String FLY_OTHERS_PERMISSION = "paradigm.fly.others";
    public static final String CLEARINV_PERMISSION = "paradigm.clearinv";
    public static final String CLEARINV_OTHERS_PERMISSION = "paradigm.clearinv.others";
    public static final String TIME_PERMISSION = "paradigm.time";
    public static final String WEATHER_PERMISSION = "paradigm.weather";
    public static final String SPEED_PERMISSION = "paradigm.speed";
    public static final String SPEED_OTHERS_PERMISSION = "paradigm.speed.others";
    public static final String FEED_PERMISSION = "paradigm.feed";
    public static final String FEED_OTHERS_PERMISSION = "paradigm.feed.others";
    public static final String HEAL_PERMISSION = "paradigm.heal";
    public static final String HEAL_OTHERS_PERMISSION = "paradigm.heal.others";
    public static final String HOME_USE_PERMISSION = "paradigm.home";
    public static final String HOME_SET_PERMISSION = "paradigm.sethome";
    public static final String HOME_DEL_PERMISSION = "paradigm.delhome";
    public static final String HOME_LIST_PERMISSION = "paradigm.homes";
    public static final String HOME_LIMIT_PERMISSION_PREFIX = "paradigm.home.limit.";
    public static final String HOME_LIMIT_UNLIMITED_PERMISSION = "paradigm.home.limit.unlimited";
    public static final String BACK_PERMISSION = "paradigm.back";
    public static final String TPA_PERMISSION = "paradigm.tpa";
    public static final String TPAHERE_PERMISSION = "paradigm.tpahere";
    public static final String TPACCEPT_PERMISSION = "paradigm.tpaccept";
    public static final String TPDENY_PERMISSION = "paradigm.tpdeny";
    public static final String TPCANCEL_PERMISSION = "paradigm.tpcancel";
    public static final String WARP_USE_PERMISSION = "paradigm.warp";
    public static final String WARP_WILDCARD_PERMISSION = "paradigm.warp.*";
    public static final String WARP_SET_PERMISSION = "paradigm.warp.set";
    public static final String WARP_DELETE_PERMISSION = "paradigm.warp.delete";
    public static final String WARP_LIST_PERMISSION = "paradigm.warps";
    public static final String WARP_INFO_PERMISSION = "paradigm.warp.info";
    public static final String KICK_PERMISSION = "paradigm.kick";
    public static final String BAN_PERMISSION = "paradigm.ban";
    public static final String TEMPBAN_PERMISSION = "paradigm.tempban";
    public static final String IPBAN_PERMISSION = "paradigm.ipban";
    public static final String MUTE_PERMISSION = "paradigm.mute";
    public static final String TEMPMUTE_PERMISSION = "paradigm.tempmute";
    public static final String WARN_PERMISSION = "paradigm.warn";
    public static final String JAIL_PERMISSION = "paradigm.jail";
    public static final String JAIL_MANAGE_PERMISSION = "paradigm.jail.manage";
    public static final String DASHBOARD_MANAGE_PERMISSION = "paradigm.dashboard.manage";
    public static final String VANISH_PERMISSION = "paradigm.vanish";
    public static final String VANISH_OTHERS_PERMISSION = "paradigm.vanish.others";
    public static final String GOD_PERMISSION = "paradigm.god";
    public static final String GOD_OTHERS_PERMISSION = "paradigm.god.others";
    public static final String INVSEE_PERMISSION = "paradigm.invsee";
    public static final String ENDERSEE_PERMISSION = "paradigm.endersee";
    public static final String REPAIR_PERMISSION = "paradigm.repair";
    public static final String REPAIR_OTHERS_PERMISSION = "paradigm.repair.others";
    public static final String ENCHANT_PERMISSION = "paradigm.enchant";
    public static final String ENCHANT_OTHERS_PERMISSION = "paradigm.enchant.others";
    public static final String SUDO_PERMISSION = "paradigm.sudo";
    public static final String NEAR_PERMISSION = "paradigm.near";
    public static final String WHOIS_PERMISSION = "paradigm.whois";
    public static final String TOP_PERMISSION = "paradigm.top";
    public static final String JUMP_PERMISSION = "paradigm.jump";

    public static final int MENTION_EVERYONE_PERMISSION_LEVEL = 2;
    public static final int MENTION_PLAYER_PERMISSION_LEVEL = 0;
    public static final int BROADCAST_PERMISSION_LEVEL = 2;
    public static final int RESTART_MANAGE_PERMISSION_LEVEL = 2;
    public static final int RELOAD_PERMISSION_LEVEL = 2;
    public static final int COMMAND_TOGGLE_PERMISSION_LEVEL = 2;
    public static final int STORAGE_MANAGE_PERMISSION_LEVEL = 2;
    public static final int GROUP_MANAGE_PERMISSION_LEVEL = 2;
    public static final int EDITOR_PERMISSION_LEVEL = 2;
    public static final int PRIVATE_MESSAGE_PERMISSION_LEVEL = 0;
    public static final int PRIVATE_REPLY_PERMISSION_LEVEL = 0;
    public static final int SOCIALSPY_PERMISSION_LEVEL = 2;
    public static final int SPAWN_PERMISSION_LEVEL = 0;
    public static final int SETSPAWN_PERMISSION_LEVEL = 2;
    public static final int SEEN_PERMISSION_LEVEL = 0;
    public static final int IGNORE_PERMISSION_LEVEL = 0;
    public static final int GAMEMODE_PERMISSION_LEVEL = 2;
    public static final int GAMEMODE_OTHERS_PERMISSION_LEVEL = 2;
    public static final int FLY_PERMISSION_LEVEL = 2;
    public static final int FLY_OTHERS_PERMISSION_LEVEL = 2;
    public static final int CLEARINV_PERMISSION_LEVEL = 2;
    public static final int CLEARINV_OTHERS_PERMISSION_LEVEL = 2;
    public static final int TIME_PERMISSION_LEVEL = 2;
    public static final int WEATHER_PERMISSION_LEVEL = 2;
    public static final int SPEED_PERMISSION_LEVEL = 2;
    public static final int SPEED_OTHERS_PERMISSION_LEVEL = 2;
    public static final int FEED_PERMISSION_LEVEL = 2;
    public static final int FEED_OTHERS_PERMISSION_LEVEL = 2;
    public static final int HEAL_PERMISSION_LEVEL = 2;
    public static final int HEAL_OTHERS_PERMISSION_LEVEL = 2;
    public static final int HOME_USE_PERMISSION_LEVEL = 0;
    public static final int HOME_SET_PERMISSION_LEVEL = 0;
    public static final int HOME_DEL_PERMISSION_LEVEL = 0;
    public static final int HOME_LIST_PERMISSION_LEVEL = 0;
    public static final int BACK_PERMISSION_LEVEL = 0;
    public static final int TPA_PERMISSION_LEVEL = 0;
    public static final int TPAHERE_PERMISSION_LEVEL = 0;
    public static final int TPACCEPT_PERMISSION_LEVEL = 0;
    public static final int TPDENY_PERMISSION_LEVEL = 0;
    public static final int TPCANCEL_PERMISSION_LEVEL = 0;
    public static final int WARP_USE_PERMISSION_LEVEL = 0;
    public static final int WARP_WILDCARD_PERMISSION_LEVEL = 2;
    public static final int WARP_SET_PERMISSION_LEVEL = 2;
    public static final int WARP_DELETE_PERMISSION_LEVEL = 2;
    public static final int WARP_LIST_PERMISSION_LEVEL = 0;
    public static final int WARP_INFO_PERMISSION_LEVEL = 0;
    public static final int KICK_PERMISSION_LEVEL = 2;
    public static final int BAN_PERMISSION_LEVEL = 2;
    public static final int TEMPBAN_PERMISSION_LEVEL = 2;
    public static final int MUTE_PERMISSION_LEVEL = 2;
    public static final int TEMPMUTE_PERMISSION_LEVEL = 2;
    public static final int WARN_PERMISSION_LEVEL = 2;
    public static final int JAIL_PERMISSION_LEVEL = 2;
    public static final int JAIL_MANAGE_PERMISSION_LEVEL = 2;
    public static final int DASHBOARD_MANAGE_PERMISSION_LEVEL = 4;
    public static final int VANISH_PERMISSION_LEVEL = 2;
    public static final int VANISH_OTHERS_PERMISSION_LEVEL = 2;
    public static final int GOD_PERMISSION_LEVEL = 2;
    public static final int GOD_OTHERS_PERMISSION_LEVEL = 2;
    public static final int INVSEE_PERMISSION_LEVEL = 2;
    public static final int ENDERSEE_PERMISSION_LEVEL = 2;
    public static final int REPAIR_PERMISSION_LEVEL = 2;
    public static final int REPAIR_OTHERS_PERMISSION_LEVEL = 2;
    public static final int ENCHANT_PERMISSION_LEVEL = 2;
    public static final int ENCHANT_OTHERS_PERMISSION_LEVEL = 2;
    public static final int SUDO_PERMISSION_LEVEL = 2;
    public static final int NEAR_PERMISSION_LEVEL = 2;
    public static final int WHOIS_PERMISSION_LEVEL = 2;
    public static final int TOP_PERMISSION_LEVEL = 2;
    public static final int JUMP_PERMISSION_LEVEL = 2;

    public PermissionsHandler(Logger logger, CMConfig cmConfig, DebugLogger debugLogger, IPlatformAdapter platform, PlayerDataStore playerDataStore, StorageService storageService) {
        this.logger = logger;
        this.cmConfig = cmConfig;
        this.debugLogger = debugLogger;
        this.platform = platform;
        this.storageService = storageService;
        this.internalPermissionApi = new PermissionAPI(
                logger,
                debugLogger,
                new PermissionDataStore(logger, debugLogger, platform != null ? platform.getConfig() : null),
                playerDataStore
        );
        if (storageService != null && storageService.isSqlActive()) {
            this.internalPermissionApi.setPermissionRepository(storageService.permissions());
            this.internalPermissionApi.setAsyncPersistenceExecutor(storageService::runStorageAsync);
        }
        this.internalPermissionApi.setContextResolver(new PermissionContextResolver(() ->
                storageService != null && storageService.context() != null ? storageService.context().serverIdentity() : null));
        this.permissionNodeRegistry = new PermissionNodeRegistry(logger, debugLogger, platform != null ? platform.getConfig() : null);
        Placeholders.setPermissionMetaResolver(this::resolvePermissionMeta);
    }

    public void initialize() {
        if (isInternalPermissionsEnabled()) {
            internalPermissionApi.initialize();
        }
    }

    /** Re-evaluates the config toggle without requiring a new Services graph. */
    public void refreshInternalPermissions() {
        if (isInternalPermissionsEnabled()) {
            internalPermissionApi.initialize();
        }
    }

    public void registerLuckPermsPermissions() {
        registerPermissionsWithLuckPerms();
    }

    private void registerPermissionsWithLuckPerms() {
        if (!isLuckPermsAvailable()) {
            return;
        }
        registerPermissionsWithLuckPermsRetry(0);
    }

    private static boolean isLuckPermsAvailable() {
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider", false, PermissionsHandler.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void registerPermissionsWithLuckPermsRetry(int attemptCount) {
        if (attemptCount >= 5) {
            logger.warn("Paradigm: Failed to register permissions with LuckPerms after {} attempts.", attemptCount);
            return;
        }

        try {
            net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
            Map<String, String> allPermissions = knownPermissionNodes();

            debugLogger.debugLog("Preparing LuckPerms integration with " + allPermissions.size() + " known permission nodes.");

            warmupLuckPermsPermissionDiscovery(api, allPermissions);
            logger.info("Paradigm: LuckPerms integration initialized with {} known permission nodes.", allPermissions.size());
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("API isn't loaded")) {
                debugLogger.debugLog("LuckPerms not ready yet, retrying in " + (attemptCount + 1) + " seconds... (attempt " + (attemptCount + 1) + "/5)");
                scheduleRetry(attemptCount);
            } else {
                logger.warn("Paradigm: Failed to register permissions with LuckPerms: {}", e.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Paradigm: Failed to register permissions with LuckPerms: {}", e.getMessage());
        }
    }

    private void warmupLuckPermsPermissionDiscovery(net.luckperms.api.LuckPerms api, Map<String, String> allPermissions) {
        if (api == null || allPermissions == null || allPermissions.isEmpty()) return;

        warmupLuckPermsGroups(api, allPermissions);

        Set<UUID> warmedUsers = new HashSet<>();

        for (net.luckperms.api.model.user.User user : api.getUserManager().getLoadedUsers()) {
            if (user == null) continue;
            warmedUsers.add(user.getUniqueId());
            warmupUserPermissions(user, allPermissions);
        }

        // Command and PermissionAPI discovery can run before the loader has attached its server instance.
        // Loaded LuckPerms groups/users are still safe to warm at that point; online players are not.
        if (platform == null || platform.getMinecraftServer() == null) return;

        List<IPlayer> onlinePlayers = platform.getOnlinePlayers();
        if (onlinePlayers == null) return;

        for (IPlayer onlinePlayer : onlinePlayers) {
            if (onlinePlayer == null || onlinePlayer.getUUID() == null) continue;

            UUID uuid;
            try {
                uuid = UUID.fromString(onlinePlayer.getUUID());
            } catch (Throwable ignored) {
                continue;
            }

            if (warmedUsers.contains(uuid)) continue;

            net.luckperms.api.model.user.User cachedUser = api.getUserManager().getUser(uuid);
            if (cachedUser != null) {
                warmupUserPermissions(cachedUser, allPermissions);
                continue;
            }

            api.getUserManager().loadUser(uuid).thenAccept(user -> {
                if (user != null) {
                    warmupUserPermissions(user, allPermissions);
                }
            }).exceptionally(ex -> {
                debugLogger.debugLog("LuckPerms user warm-up load failed for " + uuid + ": " + ex);
                return null;
            });
        }
    }

    private void warmupLuckPermsGroups(net.luckperms.api.LuckPerms api, Map<String, String> allPermissions) {
        if (api == null || allPermissions == null || allPermissions.isEmpty()) return;

        for (net.luckperms.api.model.group.Group group : api.getGroupManager().getLoadedGroups()) {
            warmupGroupPermissions(group, allPermissions);
        }

        api.getGroupManager().loadGroup("default").thenAccept(optionalGroup -> {
            if (optionalGroup != null) {
                optionalGroup.ifPresent(group -> warmupGroupPermissions(group, allPermissions));
            }
        }).exceptionally(ex -> {
            debugLogger.debugLog("LuckPerms group warm-up load failed for default: " + ex);
            return null;
        });
    }

    private void warmupGroupPermissions(net.luckperms.api.model.group.Group group, Map<String, String> allPermissions) {
        if (group == null || allPermissions == null || allPermissions.isEmpty()) return;

        for (String permission : allPermissions.keySet()) {
            try {
                group.getCachedData().getPermissionData().checkPermission(permission);
            } catch (Throwable ignored) {
            }
        }
    }

    private void warmupUserPermissions(net.luckperms.api.model.user.User user, Map<String, String> allPermissions) {
        if (user == null || allPermissions == null || allPermissions.isEmpty()) return;

        for (String permission : allPermissions.keySet()) {
            try {
                user.getCachedData().getPermissionData().checkPermission(permission);
            } catch (Throwable ignored) {
            }
        }
    }

    private void warmupUserPermissionsOnce(net.luckperms.api.model.user.User user) {
        if (user == null) return;
        UUID uuid = user.getUniqueId();
        if (uuid == null || !discoveryWarmedUsers.add(uuid)) return;
        warmupUserPermissions(user, knownPermissionNodes());
    }

    private void scheduleRetry(int attemptCount) {
        Thread retryThread = new Thread(() -> {
            try {
                Thread.sleep((attemptCount + 1) * 1000L);
                registerPermissionsWithLuckPermsRetry(attemptCount + 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Paradigm-LuckPerms-Retry");
        retryThread.setDaemon(true);
        retryThread.start();
    }

    public void refreshCustomCommandPermissions() {
        if (isInternalPermissionsEnabled()) {
            if (storageService != null && storageService.isSqlActive()) {
                storageService.runStorageAsync("permissions.reload", internalPermissionApi::reload);
            } else {
                internalPermissionApi.reload();
            }
        }
        registerPermissionsWithLuckPerms();
    }

    public int discoverCommandTreeFromServer(Object server) {
        if (!isExternalCommandPermissionsEnabled() || server == null) {
            return 0;
        }
        Object commands = invokeNoArg(server, "getCommands");
        if (commands == null) commands = invokeNoArg(server, "getCommandManager");
        if (commands == null) commands = invokeNoArg(server, "commands");
        if (commands == null) commands = invokeNoArg(server, "commandManager");
        Object dispatcher = commands != null ? invokeNoArg(commands, "getDispatcher") : null;
        if (dispatcher == null && commands != null) dispatcher = invokeNoArg(commands, "dispatcher");
        return discoverCommandTree(dispatcher);
    }

    public int discoverCommandTree(Object dispatcher) {
        if (!isExternalCommandPermissionsEnabled() || dispatcher == null) {
            return 0;
        }
        int changed = permissionNodeRegistry.discoverCommandTree(dispatcher);
        if (changed > 0) {
            registerPermissionsWithLuckPerms();
        }
        return changed;
    }

    public void registerExternalPermissionNode(String node, String source, String description, int defaultLevel) {
        if (node == null || node.isBlank()) {
            return;
        }
        permissionNodeRegistry.registerNode(node, source, description, defaultLevel);
        // Forge/NeoForge PermissionAPI discovery reports nodes one at a time. Re-warming every
        // LuckPerms subject for each callback makes startup quadratic. The normal discovery and
        // server-start registration passes consume the complete registry as one batch.
    }

    public List<PermissionNodeRegistry.DiscoveredPermission> listDiscoveredPermissionNodes(String query, int limit) {
        return permissionNodeRegistry.listNodes(query, limit);
    }

    public CommandGuardResult evaluateCommandPermission(IPlayer player, String commandLine) {
        if (!isExternalCommandPermissionsEnabled()) {
            return CommandGuardResult.allowed(commandLine, null, "disabled");
        }
        if (player == null || commandLine == null || commandLine.isBlank()) {
            return CommandGuardResult.allowed(commandLine, null, "empty");
        }

        Set<String> candidates = permissionNodeRegistry.commandCandidates(commandLine);
        if (candidates.isEmpty()) {
            return CommandGuardResult.allowed(commandLine, null, "no_candidates");
        }

        for (String node : candidates) {
            Boolean explicit = queryDefinedPermission(player, node);
            if (explicit != null) {
                return explicit
                        ? CommandGuardResult.allowed(commandLine, node, "explicit_allow")
                        : CommandGuardResult.denied(commandLine, node, "explicit_deny");
            }
        }

        if (isExternalCommandStrictMode()) {
            if (hasOperatorBypass(player)) {
                return CommandGuardResult.allowed(commandLine, firstCandidate(candidates), "strict_op_fallback");
            }
            return CommandGuardResult.denied(commandLine, firstCandidate(candidates), "strict_missing_allow");
        }

        return CommandGuardResult.allowed(commandLine, firstCandidate(candidates), "undefined");
    }

    public Boolean queryDefinedPermission(IPlayer player, String permission) {
        if (player == null || permission == null || permission.isBlank()) {
            return null;
        }

        if (isLuckPermsAvailable()) {
            try {
                net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
                java.util.UUID uuid = java.util.UUID.fromString(player.getUUID());
                net.luckperms.api.model.user.User user = api.getUserManager().getUser(uuid);
                if (user != null) {
                    warmupUserPermissionsOnce(user);
                    net.luckperms.api.util.Tristate lpState = user.getCachedData().getPermissionData().checkPermission(permission);
                    if (lpState != net.luckperms.api.util.Tristate.UNDEFINED) {
                        return lpState.asBoolean();
                    }
                }
            } catch (Throwable t) {
                debugLogger.debugLog("[PermissionsHandler] LuckPerms explicit query failed for '" + permission + "': " + t);
            }
        }

        if (isInternalPermissionsEnabled()) {
            try {
                return internalPermissionApi.hasPermission(player, permission);
            } catch (Throwable t) {
                debugLogger.debugLog("[PermissionsHandler] Internal explicit query failed for '" + permission + "': " + t);
            }
        }

        return null;
    }

    public Boolean queryDefinedPermission(UUID playerUuid, String permission) {
        if (playerUuid == null || permission == null || permission.isBlank()) {
            return null;
        }

        if (isLuckPermsAvailable()) {
            try {
                net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
                net.luckperms.api.model.user.User user = api.getUserManager().getUser(playerUuid);
                if (user != null) {
                    warmupUserPermissionsOnce(user);
                    net.luckperms.api.util.Tristate lpState = user.getCachedData().getPermissionData().checkPermission(permission);
                    if (lpState != net.luckperms.api.util.Tristate.UNDEFINED) {
                        return lpState.asBoolean();
                    }
                }
            } catch (Throwable t) {
                debugLogger.debugLog("[PermissionsHandler] LuckPerms UUID explicit query failed for '" + permission + "': " + t);
            }
        }

        if (isInternalPermissionsEnabled()) {
            try {
                return internalPermissionApi.hasPermission(playerUuid, permission);
            } catch (Throwable t) {
                debugLogger.debugLog("[PermissionsHandler] Internal UUID explicit query failed for '" + permission + "': " + t);
            }
        }

        return null;
    }

    /** Cache-only explicit-context lookup used by the stable companion API. */
    public Boolean queryDefinedPermission(UUID playerUuid, String permission, PermissionContextSet context) {
        if (playerUuid == null || permission == null || permission.isBlank()) return null;
        PermissionContextSet effectiveContext = context != null ? context : PermissionContextSet.empty();

        if (isLuckPermsAvailable()) {
            try {
                Boolean result = LuckPermsPublicApiBridge.query(playerUuid, permission, effectiveContext);
                if (result != null) return result;
            } catch (Throwable t) {
                debugLogger.debugLog("[PermissionsHandler] LuckPerms contextual query failed for '" + permission + "': " + t);
            }
        }

        if (isInternalPermissionsEnabled()) {
            try {
                return internalPermissionApi.hasPermission(playerUuid, permission, effectiveContext);
            } catch (Throwable t) {
                debugLogger.debugLog("[PermissionsHandler] Internal contextual query failed for '" + permission + "': " + t);
            }
        }
        return null;
    }

    /** Applies the existing external/internal order and a typed platform OP fallback without storage access. */
    public boolean hasPermission(UUID playerUuid, String permission, int fallbackLevel, PermissionContextSet context) {
        if (playerUuid == null || permission == null || permission.isBlank()) return false;
        Boolean defined = queryDefinedPermission(playerUuid, permission, context);
        if (defined != null) return defined;
        if (fallbackLevel < 0 || platform == null) return false;
        IPlayer online = platform.getPlayerByUuid(playerUuid.toString());
        if (online == null) return false;
        try {
            return platform.hasPermission(online, permission, fallbackLevel);
        } catch (Throwable failure) {
            debugLogger.debugLog("[PermissionsHandler] Platform fallback failed for '" + permission + "': " + failure);
            return false;
        }
    }

    private static String firstCandidate(Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.iterator().next();
    }

    public boolean hasPermission(IPlayer player, String permission) {
        int fallbackLevel = vanillaLevelFor(permission);
        return hasPermission(player, permission, fallbackLevel);
    }

    public boolean hasPermission(IPlayer player, String permission, int vanillaLevelFallback) {
        if (player == null) {
            debugLogger.debugLog("[PermissionsHandler] hasPermission called with null player for: " + permission);
            return false;
        }
        if (permission == null || permission.isBlank()) {
            debugLogger.debugLog("[PermissionsHandler] hasPermission called with blank permission for player: " + player.getName());
            return true;
        }

        debugLogger.debugLog("[PermissionsHandler] Checking permission '" + permission + "' for player: " + player.getName());

        if (isLuckPermsAvailable()) {
            try {
                net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
                java.util.UUID uuid = java.util.UUID.fromString(player.getUUID());

                net.luckperms.api.model.user.User user = api.getUserManager().getUser(uuid);
                if (user != null) {
                    warmupUserPermissionsOnce(user);
                    net.luckperms.api.util.Tristate lpState = user.getCachedData().getPermissionData().checkPermission(permission);
                    if (lpState != net.luckperms.api.util.Tristate.UNDEFINED) {
                        boolean lpResult = lpState.asBoolean();
                        debugLogger.debugLog("[PermissionsHandler] LuckPerms check for '" + permission + "' -> " + lpResult + " (defined)");
                        return lpResult;
                    }
                    debugLogger.debugLog("[PermissionsHandler] LuckPerms check for '" + permission + "' is UNDEFINED, falling back to internal/vanilla.");
                }

                debugLogger.debugLog("[PermissionsHandler] LuckPerms user not cached yet for player: " + player.getName() + ", continuing with internal/vanilla fallback.");
            } catch (Throwable t) {
                debugLogger.debugLog("[PermissionsHandler] LuckPerms check failed: " + t);
            }
        }

        if (isInternalPermissionsEnabled()) {
            Boolean internalResult = internalPermissionApi.hasPermission(player, permission);
            if (internalResult != null) {
                if (!internalResult && hasOperatorBypass(player)) {
                    debugLogger.debugLog("[PermissionsHandler] Internal PermissionAPI denied '" + permission + "', but OP bypass is active for player: " + player.getName());
                    return true;
                }
                debugLogger.debugLog("[PermissionsHandler] Internal PermissionAPI check for '" + permission + "' -> " + internalResult);
                return internalResult;
            }
        } else {
            debugLogger.debugLog("[PermissionsHandler] Internal permissions are disabled, skipping PermissionAPI check.");
        }

        if (vanillaLevelFallback < 0) {
            debugLogger.debugLog("[PermissionsHandler] No vanilla fallback level for '" + permission + "', returning false.");
            return false;
        }

        debugLogger.debugLog("[PermissionsHandler] Using vanilla fallback for '" + permission + "' with level=" + vanillaLevelFallback);
        try {
            boolean vanillaResult = hasVanillaPermissionLevel(player, vanillaLevelFallback);
            debugLogger.debugLog("[PermissionsHandler] Vanilla check result: " + vanillaResult);
            return vanillaResult;
        } catch (Throwable t) {
            debugLogger.debugLog("[PermissionsHandler] Vanilla check failed: " + t);
        }

        debugLogger.debugLog("[PermissionsHandler] All checks failed for '" + permission + "', returning false");
        return false;
    }

    /** Cache-only permission lookup for authenticated actors that are no longer tied to a command source. */
    public boolean hasPermission(String playerUuid, String permission, int vanillaLevelFallback) {
        if (playerUuid == null || playerUuid.isBlank()) return false;
        IPlayer online = platform != null ? platform.getPlayerByUuid(playerUuid) : null;
        if (online != null) return hasPermission(online, permission, vanillaLevelFallback);
        java.util.UUID uuid;
        try { uuid = java.util.UUID.fromString(playerUuid); }
        catch (IllegalArgumentException ignored) { return false; }

        if (isLuckPermsAvailable()) {
            try {
                var user = net.luckperms.api.LuckPermsProvider.get().getUserManager().getUser(uuid);
                if (user != null) {
                    var state = user.getCachedData().getPermissionData().checkPermission(permission);
                    if (state != net.luckperms.api.util.Tristate.UNDEFINED) return state.asBoolean();
                }
            } catch (Throwable ignored) { }
        }
        if (isInternalPermissionsEnabled()) {
            Boolean result = internalPermissionApi.hasPermission(uuid, permission);
            if (result != null) return result;
        }
        return false;
    }

    private static boolean hasOperatorBypass(IPlayer player) {
        try {
            // Level 2 is the common admin/operator threshold across loaders.
            return hasVanillaPermissionLevel(player, 2);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean hasStrictVanillaPermissionLevel(IPlayer player, int level) {
        return hasVanillaPermissionLevel(player, level);
    }

    private static boolean hasVanillaPermissionLevel(IPlayer player, int level) {
        if (player == null) return false;
        Object original = player.getOriginalPlayer();
        if (original == null) return false;

        Boolean directResult = invokeBooleanIntMethod(original, level, "hasPermissions", "hasPermissionLevel");
        if (directResult != null) {
            return directResult;
        }

        // Command source based checks (common across multiple mappings).
        Object source = invokeNoArg(original, "createCommandSourceStack");
        if (source == null) source = invokeNoArg(original, "getCommandSource");
        if (source == null) source = invokeNoArg(original, "getCommandSourceStack");
        if (source != null) {
            Boolean sourceResult = invokeBooleanIntMethod(source, level, "hasPermission", "hasPermissionLevel");
            if (sourceResult != null) {
                return sourceResult;
            }
        }

        // Last fallback: check server operator list directly.
        try {
            Object server = invokeNoArg(original, "getServer");
            if (server != null) {
                Object profile = invokeNoArg(original, "getGameProfile");
                if (profile != null) {
                    Object playerManager = invokeNoArg(server, "getPlayerList");
                    if (playerManager == null) playerManager = invokeNoArg(server, "getPlayerManager");
                    if (playerManager != null) {
                        Boolean opResult = invokeBooleanSingleArgMethod(playerManager, profile, "isOp", "isOperator");
                        if (opResult != null) {
                            return opResult;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null || name == null || name.isBlank()) {
            return null;
        }
        try {
            java.lang.reflect.Method m = findMethod(target.getClass(), name);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Boolean invokeBooleanIntMethod(Object target, int arg, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String methodName : methodNames) {
            try {
                java.lang.reflect.Method m = findMethod(target.getClass(), methodName, int.class);
                if (m == null) {
                    continue;
                }
                m.setAccessible(true);
                Object result = m.invoke(target, arg);
                if (result instanceof Boolean b) {
                    return b;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Boolean invokeBooleanSingleArgMethod(Object target, Object arg, String... methodNames) {
        if (target == null || arg == null || methodNames == null) {
            return null;
        }
        Class<?> argClass = arg.getClass();
        for (String methodName : methodNames) {
            try {
                for (java.lang.reflect.Method m : target.getClass().getMethods()) {
                    if (!m.getName().equals(methodName) || m.getParameterCount() != 1) {
                        continue;
                    }
                    Class<?> param = m.getParameterTypes()[0];
                    if (!param.isAssignableFrom(argClass)) {
                        continue;
                    }
                    m.setAccessible(true);
                    Object result = m.invoke(target, arg);
                    if (result instanceof Boolean b) {
                        return b;
                    }
                }

                for (java.lang.Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if (!m.getName().equals(methodName) || m.getParameterCount() != 1) {
                            continue;
                        }
                        Class<?> param = m.getParameterTypes()[0];
                        if (!param.isAssignableFrom(argClass)) {
                            continue;
                        }
                        m.setAccessible(true);
                        Object result = m.invoke(target, arg);
                        if (result instanceof Boolean b) {
                            return b;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static java.lang.reflect.Method findMethod(Class<?> type, String name, Class<?>... params) {
        if (type == null || name == null || name.isBlank()) {
            return null;
        }

        try {
            return type.getMethod(name, params);
        } catch (Throwable ignored) {
        }

        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int vanillaLevelFor(String permission) {
        if (permission == null) return 0;
        if (permission.equals(MENTION_EVERYONE_PERMISSION)) return MENTION_EVERYONE_PERMISSION_LEVEL;
        if (permission.equals(MENTION_PLAYER_PERMISSION)) return MENTION_PLAYER_PERMISSION_LEVEL;
        if (permission.equals(BROADCAST_PERMISSION)) return BROADCAST_PERMISSION_LEVEL;
        if (permission.equals(RESTART_MANAGE_PERMISSION)) return RESTART_MANAGE_PERMISSION_LEVEL;

        if (permission.equals(STAFF_CHAT_PERMISSION)) return 2;
        if (permission.equals(GROUPCHAT_PERMISSION)) return 2;
        if (permission.equals(RELOAD_PERMISSION)) return RELOAD_PERMISSION_LEVEL;
        if (permission.equals(COMMAND_TOGGLE_PERMISSION)) return COMMAND_TOGGLE_PERMISSION_LEVEL;
        if (permission.equals(STORAGE_MANAGE_PERMISSION)) return STORAGE_MANAGE_PERMISSION_LEVEL;
        if (permission.equals(GROUP_MANAGE_PERMISSION)) return GROUP_MANAGE_PERMISSION_LEVEL;
        if (permission.equals(EDITOR_PERMISSION)) return EDITOR_PERMISSION_LEVEL;
        if (permission.equals(PRIVATE_MESSAGE_PERMISSION)) return PRIVATE_MESSAGE_PERMISSION_LEVEL;
        if (permission.equals(PRIVATE_REPLY_PERMISSION)) return PRIVATE_REPLY_PERMISSION_LEVEL;
        if (permission.equals(SOCIALSPY_PERMISSION)) return SOCIALSPY_PERMISSION_LEVEL;
        if (permission.equals(SPAWN_PERMISSION)) return SPAWN_PERMISSION_LEVEL;
        if (permission.equals(SETSPAWN_PERMISSION)) return SETSPAWN_PERMISSION_LEVEL;
        if (permission.equals(SEEN_PERMISSION)) return SEEN_PERMISSION_LEVEL;
        if (permission.equals(IGNORE_PERMISSION)) return IGNORE_PERMISSION_LEVEL;
        if (permission.equals(GAMEMODE_PERMISSION)) return GAMEMODE_PERMISSION_LEVEL;
        if (permission.equals(GAMEMODE_OTHERS_PERMISSION)) return GAMEMODE_OTHERS_PERMISSION_LEVEL;
        if (permission.equals(FLY_PERMISSION)) return FLY_PERMISSION_LEVEL;
        if (permission.equals(FLY_OTHERS_PERMISSION)) return FLY_OTHERS_PERMISSION_LEVEL;
        if (permission.equals(CLEARINV_PERMISSION)) return CLEARINV_PERMISSION_LEVEL;
        if (permission.equals(CLEARINV_OTHERS_PERMISSION)) return CLEARINV_OTHERS_PERMISSION_LEVEL;
        if (permission.equals(TIME_PERMISSION)) return TIME_PERMISSION_LEVEL;
        if (permission.equals(WEATHER_PERMISSION)) return WEATHER_PERMISSION_LEVEL;
        if (permission.equals(SPEED_PERMISSION)) return SPEED_PERMISSION_LEVEL;
        if (permission.equals(SPEED_OTHERS_PERMISSION)) return SPEED_OTHERS_PERMISSION_LEVEL;
        if (permission.equals(FEED_PERMISSION)) return FEED_PERMISSION_LEVEL;
        if (permission.equals(FEED_OTHERS_PERMISSION)) return FEED_OTHERS_PERMISSION_LEVEL;
        if (permission.equals(HEAL_PERMISSION)) return HEAL_PERMISSION_LEVEL;
        if (permission.equals(HEAL_OTHERS_PERMISSION)) return HEAL_OTHERS_PERMISSION_LEVEL;
        if (permission.equals(HOME_USE_PERMISSION)) return HOME_USE_PERMISSION_LEVEL;
        if (permission.equals(HOME_SET_PERMISSION)) return HOME_SET_PERMISSION_LEVEL;
        if (permission.equals(HOME_DEL_PERMISSION)) return HOME_DEL_PERMISSION_LEVEL;
        if (permission.equals(HOME_LIST_PERMISSION)) return HOME_LIST_PERMISSION_LEVEL;
        if (permission.equals(BACK_PERMISSION)) return BACK_PERMISSION_LEVEL;
        if (permission.equals(TPA_PERMISSION)) return TPA_PERMISSION_LEVEL;
        if (permission.equals(TPAHERE_PERMISSION)) return TPAHERE_PERMISSION_LEVEL;
        if (permission.equals(TPACCEPT_PERMISSION)) return TPACCEPT_PERMISSION_LEVEL;
        if (permission.equals(TPDENY_PERMISSION)) return TPDENY_PERMISSION_LEVEL;
        if (permission.equals(TPCANCEL_PERMISSION)) return TPCANCEL_PERMISSION_LEVEL;
        if (permission.equals(WARP_USE_PERMISSION)) return WARP_USE_PERMISSION_LEVEL;
        if (permission.equals(WARP_WILDCARD_PERMISSION)) return WARP_WILDCARD_PERMISSION_LEVEL;
        if (permission.equals(WARP_SET_PERMISSION)) return WARP_SET_PERMISSION_LEVEL;
        if (permission.equals(WARP_DELETE_PERMISSION)) return WARP_DELETE_PERMISSION_LEVEL;
        if (permission.equals(WARP_LIST_PERMISSION)) return WARP_LIST_PERMISSION_LEVEL;
        if (permission.equals(WARP_INFO_PERMISSION)) return WARP_INFO_PERMISSION_LEVEL;
        if (permission.equals(KICK_PERMISSION)) return KICK_PERMISSION_LEVEL;
        if (permission.equals(BAN_PERMISSION)) return BAN_PERMISSION_LEVEL;
        if (permission.equals(TEMPBAN_PERMISSION)) return TEMPBAN_PERMISSION_LEVEL;
        if (permission.equals(IPBAN_PERMISSION)) return BAN_PERMISSION_LEVEL;
        if (permission.equals(MUTE_PERMISSION)) return MUTE_PERMISSION_LEVEL;
        if (permission.equals(TEMPMUTE_PERMISSION)) return TEMPMUTE_PERMISSION_LEVEL;
        if (permission.equals(WARN_PERMISSION)) return WARN_PERMISSION_LEVEL;
        if (permission.equals(JAIL_PERMISSION)) return JAIL_PERMISSION_LEVEL;
        if (permission.equals(JAIL_MANAGE_PERMISSION)) return JAIL_MANAGE_PERMISSION_LEVEL;
        if (permission.equals(DASHBOARD_MANAGE_PERMISSION)) return DASHBOARD_MANAGE_PERMISSION_LEVEL;
        if (permission.equals(VANISH_PERMISSION)) return VANISH_PERMISSION_LEVEL;
        if (permission.equals(VANISH_OTHERS_PERMISSION)) return VANISH_OTHERS_PERMISSION_LEVEL;
        if (permission.equals(GOD_PERMISSION)) return GOD_PERMISSION_LEVEL;
        if (permission.equals(GOD_OTHERS_PERMISSION)) return GOD_OTHERS_PERMISSION_LEVEL;
        if (permission.equals(INVSEE_PERMISSION)) return INVSEE_PERMISSION_LEVEL;
        if (permission.equals(ENDERSEE_PERMISSION)) return ENDERSEE_PERMISSION_LEVEL;
        if (permission.equals(REPAIR_PERMISSION)) return REPAIR_PERMISSION_LEVEL;
        if (permission.equals(REPAIR_OTHERS_PERMISSION)) return REPAIR_OTHERS_PERMISSION_LEVEL;
        if (permission.equals(ENCHANT_PERMISSION)) return ENCHANT_PERMISSION_LEVEL;
        if (permission.equals(ENCHANT_OTHERS_PERMISSION)) return ENCHANT_OTHERS_PERMISSION_LEVEL;
        if (permission.equals(SUDO_PERMISSION)) return SUDO_PERMISSION_LEVEL;
        if (permission.equals(NEAR_PERMISSION)) return NEAR_PERMISSION_LEVEL;
        if (permission.equals(WHOIS_PERMISSION)) return WHOIS_PERMISSION_LEVEL;
        if (permission.equals(TOP_PERMISSION)) return TOP_PERMISSION_LEVEL;
        if (permission.equals(JUMP_PERMISSION)) return JUMP_PERMISSION_LEVEL;

        return -1;
    }

    public Map<String, String> knownPermissionNodes() {
        Map<String, String> nodes = new LinkedHashMap<>();

        nodes.put(STAFF_CHAT_PERMISSION, "Access to /sc (Staff Chat) and receiving staff messages.");
        nodes.put(MENTION_EVERYONE_PERMISSION, "Allows using @everyone to ping all players in Mentions module.");
        nodes.put(MENTION_PLAYER_PERMISSION, "Allows mentioning individual players in Mentions module.");
        nodes.put(RESTART_MANAGE_PERMISSION, "Allows managing restarts: /restart now, /restart cancel.");
        nodes.put(BROADCAST_PERMISSION, "Allows using /paradigm broadcast, actionbar, title, and bossbar commands.");
        nodes.put(GROUPCHAT_PERMISSION, "Allows using /groupchat commands (create, invite, join, etc.).");
        nodes.put(RELOAD_PERMISSION, "Allows using /paradigm reload and /customcommandsreload commands.");
        nodes.put(COMMAND_TOGGLE_PERMISSION, "Allows enabling/disabling Paradigm commands at runtime via /paradigm command.");
        nodes.put(STORAGE_MANAGE_PERMISSION, "Allows viewing and testing Paradigm storage providers with /paradigm storage.");
        nodes.put(GROUP_MANAGE_PERMISSION, "Allows managing internal permission groups via /paradigm group.");
        nodes.put(EDITOR_PERMISSION, "Allows using /paradigm editor and /paradigm apply.");
        nodes.put(PRIVATE_MESSAGE_PERMISSION, "Allows sending private messages with /msg.");
        nodes.put(PRIVATE_REPLY_PERMISSION, "Allows replying to private messages with /reply.");
        nodes.put(SOCIALSPY_PERMISSION, "Allows toggling /socialspy and receiving mirrored private messages.");
        nodes.put(SPAWN_PERMISSION, "Allows teleporting to server spawn with /spawn.");
        nodes.put(SETSPAWN_PERMISSION, "Allows setting server spawn with /setspawn.");
        nodes.put(SEEN_PERMISSION, "Allows checking when a player was last seen with /seen.");
        nodes.put(IGNORE_PERMISSION, "Allows managing ignored players with /ignore and /unignore.");
        nodes.put(GAMEMODE_PERMISSION, "Allows changing gamemode with /gamemode and gamemode aliases.");
        nodes.put(GAMEMODE_OTHERS_PERMISSION, "Allows changing another player's gamemode.");
        nodes.put(FLY_PERMISSION, "Allows toggling flight with /fly.");
        nodes.put(FLY_OTHERS_PERMISSION, "Allows toggling flight for another player.");
        nodes.put(CLEARINV_PERMISSION, "Allows clearing inventories with /clearinv and /ci.");
        nodes.put(CLEARINV_OTHERS_PERMISSION, "Allows clearing another player's inventory.");
        nodes.put(TIME_PERMISSION, "Allows changing time with /day and /night.");
        nodes.put(WEATHER_PERMISSION, "Allows changing weather with /sun, /rain, /thunder.");
        nodes.put(SPEED_PERMISSION, "Allows using /speed.");
        nodes.put(SPEED_OTHERS_PERMISSION, "Allows changing another player's speed.");
        nodes.put(FEED_PERMISSION, "Allows using /feed.");
        nodes.put(FEED_OTHERS_PERMISSION, "Allows feeding another player.");
        nodes.put(HEAL_PERMISSION, "Allows using /heal.");
        nodes.put(HEAL_OTHERS_PERMISSION, "Allows healing another player.");
        nodes.put(HOME_USE_PERMISSION, "Allows teleporting to home with /home.");
        nodes.put(HOME_SET_PERMISSION, "Allows setting home locations with /sethome.");
        nodes.put(HOME_DEL_PERMISSION, "Allows deleting homes with /delhome.");
        nodes.put(HOME_LIST_PERMISSION, "Allows listing homes with /homes.");
        nodes.put(HOME_LIMIT_PERMISSION_PREFIX + "<number>", "Maximum number of homes a player can have (e.g. paradigm.home.limit.3).");
        nodes.put(HOME_LIMIT_UNLIMITED_PERMISSION, "Removes home count limit for the player/group.");
        nodes.put(BACK_PERMISSION, "Allows returning to previous location with /back.");
        nodes.put(TPA_PERMISSION, "Allows sending teleport requests with /tpa.");
        nodes.put(TPAHERE_PERMISSION, "Allows sending summon requests with /tpahere.");
        nodes.put(TPACCEPT_PERMISSION, "Allows accepting teleport requests with /tpaccept.");
        nodes.put(TPDENY_PERMISSION, "Allows denying teleport requests with /tpdeny.");
        nodes.put(TPCANCEL_PERMISSION, "Allows cancelling outgoing teleport requests with /tpcancel.");
        nodes.put(WARP_USE_PERMISSION, "Allows using /warp commands globally.");
        nodes.put(WARP_WILDCARD_PERMISSION, "Allows using all named warp permissions paradigm.warp.<name>.");
        nodes.put(WARP_SET_PERMISSION, "Allows setting global warps with /setwarp.");
        nodes.put(WARP_DELETE_PERMISSION, "Allows deleting global warps with /delwarp.");
        nodes.put(WARP_LIST_PERMISSION, "Allows listing global warps with /warps.");
        nodes.put(WARP_INFO_PERMISSION, "Allows viewing warp details with /warpinfo.");
        nodes.put(KICK_PERMISSION, "Allows kicking online players with /kick.");
        nodes.put(BAN_PERMISSION, "Allows banning and unbanning players with /ban and /unban.");
        nodes.put(TEMPBAN_PERMISSION, "Allows temporary bans with /tempban.");
        nodes.put(IPBAN_PERMISSION, "Allows IP bans with /ipban, /tempipban, and /unipban.");
        nodes.put(MUTE_PERMISSION, "Allows muting and unmuting players with /mute and /unmute.");
        nodes.put(TEMPMUTE_PERMISSION, "Allows temporary mutes with /tempmute.");
        nodes.put(WARN_PERMISSION, "Allows warning players with /warn.");
        nodes.put(JAIL_PERMISSION, "Allows jailing and unjailing players with /jail and /unjail.");
        nodes.put(JAIL_MANAGE_PERMISSION, "Allows setting the jail location with /setjail.");
        nodes.put(DASHBOARD_MANAGE_PERMISSION, "Allows managing and logging in to the local Paradigm admin dashboard.");
        nodes.put(VANISH_PERMISSION, "Allows toggling vanish mode.");
        nodes.put(VANISH_OTHERS_PERMISSION, "Allows toggling vanish mode for other players.");
        nodes.put(GOD_PERMISSION, "Allows toggling god mode.");
        nodes.put(GOD_OTHERS_PERMISSION, "Allows toggling god mode for other players.");
        nodes.put(INVSEE_PERMISSION, "Allows inspecting player inventories with /invsee.");
        nodes.put(ENDERSEE_PERMISSION, "Allows inspecting player ender chests with /endersee.");
        nodes.put(REPAIR_PERMISSION, "Allows repairing held items with /repair.");
        nodes.put(REPAIR_OTHERS_PERMISSION, "Allows repairing another player's items.");
        nodes.put(ENCHANT_PERMISSION, "Allows enchanting own held item with /enchant.");
        nodes.put(ENCHANT_OTHERS_PERMISSION, "Allows enchanting another player's held item.");
        nodes.put(SUDO_PERMISSION, "Allows running commands as another player with /sudo.");
        nodes.put(NEAR_PERMISSION, "Allows listing nearby players with /near.");
        nodes.put(WHOIS_PERMISSION, "Allows viewing player diagnostics with /whois.");
        nodes.put(TOP_PERMISSION, "Allows teleporting to the highest safe block with /top.");
        nodes.put(JUMP_PERMISSION, "Allows short forward teleporting with /jump.");

        if (cmConfig != null && cmConfig.getLoadedCommands() != null) {
            for (CustomCommand cmd : cmConfig.getLoadedCommands()) {
                if (cmd.isRequirePermission() && cmd.getPermission() != null && !cmd.getPermission().trim().isEmpty()) {
                    String desc = cmd.getDescription() != null && !cmd.getDescription().trim().isEmpty()
                            ? cmd.getDescription()
                            : "Custom command: /" + cmd.getName();
                    nodes.put(cmd.getPermission(), desc);
                }
            }
        }

        nodes.putAll(permissionNodeRegistry.knownNodes());

        return nodes;
    }

    private Placeholders.PermissionMeta resolvePermissionMeta(IPlayer player) {
        if (!isInternalPermissionsEnabled()) {
            return null;
        }
        PermissionAPI.PermissionMeta meta = internalPermissionApi.resolveMeta(player);
        if (meta == null) {
            return null;
        }
        return new Placeholders.PermissionMeta(
                meta.primaryGroup(),
                meta.prefix(),
                meta.suffix(),
                meta.groups()
        );
    }

    /** Cache-only metadata used by features such as chat and tablist presentation. */
    public PermissionAPI.PermissionMeta resolvePlayerMetadata(IPlayer player) {
        if (!isInternalPermissionsEnabled()) return null;
        return internalPermissionApi.resolveMeta(player);
    }

    /** Cache-only resolved metadata for the stable companion API. */
    public PermissionAPI.PermissionMeta resolvePlayerMetadata(UUID playerUuid) {
        if (playerUuid == null) return null;
        if (isInternalPermissionsEnabled()) return internalPermissionApi.resolveMeta(playerUuid);
        if (!isLuckPermsAvailable()) return null;
        try {
            return LuckPermsPublicApiBridge.metadata(playerUuid);
        } catch (Throwable failure) {
            debugLogger.debugLog("[PermissionsHandler] LuckPerms metadata lookup failed: " + failure);
            return null;
        }
    }

    public PermissionNodeRegistry.ExternalRegistration registerExternalPermissionNode(
            String ownerModId, String node, String description, int fallbackLevel,
            String category, String featureIdentifier) {
        return permissionNodeRegistry.registerExternalNode(ownerModId, node, description, fallbackLevel,
                category, featureIdentifier);
    }

    public void clearExternalPermissionNodes() {
        permissionNodeRegistry.clearExternalNodes();
    }

    public boolean createPermissionGroup(String groupName) {
        if (!isInternalPermissionsEnabled()) return false;
        return internalPermissionApi.createGroup(groupName);
    }

    public boolean deletePermissionGroup(String groupName) {
        if (!isInternalPermissionsEnabled()) return false;
        return internalPermissionApi.deleteGroup(groupName);
    }

    public boolean addPermissionGroupParent(String groupName, String parentName) {
        if (!isInternalPermissionsEnabled()) return false;
        return internalPermissionApi.addGroupParent(groupName, parentName);
    }

    public boolean removePermissionGroupParent(String groupName, String parentName) {
        if (!isInternalPermissionsEnabled()) return false;
        return internalPermissionApi.removeGroupParent(groupName, parentName);
    }

    public boolean addPermissionToGroup(String groupName, String permissionNode, boolean denied) {
        if (!isInternalPermissionsEnabled()) return false;
        return internalPermissionApi.addGroupPermission(groupName, permissionNode, denied);
    }

    public boolean addPermissionToGroup(String groupName, String permissionNode, boolean denied, eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet contextSet, Long expiresAtMs) {
        if (!isInternalPermissionsEnabled()) return false;
        return internalPermissionApi.addGroupPermission(groupName, permissionNode, denied, contextSet, expiresAtMs);
    }

    public boolean removePermissionFromGroup(String groupName, String permissionNode) {
        if (!isInternalPermissionsEnabled()) return false;
        return internalPermissionApi.removeGroupPermission(groupName, permissionNode);
    }

    public boolean removePermissionFromGroup(String groupName, String permissionNode, eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet contextSet) {
        if (!isInternalPermissionsEnabled()) return false;
        return internalPermissionApi.removeGroupPermission(groupName, permissionNode, contextSet);
    }

    public boolean removePermissionFromGroupById(String groupName, String assignmentId) {
        if (!isInternalPermissionsEnabled()) return false;
        return internalPermissionApi.removeGroupPermissionById(groupName, assignmentId);
    }

    public int countPermissionAssignmentsInGroup(String groupName, String permissionNode, eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet contextSet) {
        return isInternalPermissionsEnabled() ? internalPermissionApi.countGroupPermissionAssignments(groupName, permissionNode, contextSet) : 0;
    }

    public boolean setPermissionGroupMetadata(String groupName, String field, String value) {
        if (!isInternalPermissionsEnabled()) return false;
        return internalPermissionApi.setGroupMetadata(groupName, field, value);
    }

    public boolean assignPlayerGroup(UUID playerUuid, String groupName) {
        if (!isInternalPermissionsEnabled()) return false;
        boolean changed = internalPermissionApi.assignGroup(playerUuid, groupName);
        if (changed) {
            refreshPlayerCommandTree(playerUuid);
        }
        return changed;
    }

    public boolean assignPlayerGroupTemp(UUID playerUuid, String groupName, long expiresAtMs, String assignedBy) {
        if (!isInternalPermissionsEnabled()) return false;
        boolean changed = internalPermissionApi.assignGroup(playerUuid, groupName, expiresAtMs, assignedBy);
        if (changed) {
            refreshPlayerCommandTree(playerUuid);
        }
        return changed;
    }

    public boolean assignPlayerGroup(UUID playerUuid, String groupName, eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet contextSet, Long expiresAtMs, String assignedBy) {
        if (!isInternalPermissionsEnabled()) return false;
        boolean changed = internalPermissionApi.assignGroup(playerUuid, groupName, expiresAtMs != null ? expiresAtMs : 0L, assignedBy, contextSet);
        if (changed) {
            refreshPlayerCommandTree(playerUuid);
        }
        return changed;
    }

    public boolean revokePlayerGroup(UUID playerUuid, String groupName) {
        if (!isInternalPermissionsEnabled()) return false;
        boolean changed = internalPermissionApi.revokeGroup(playerUuid, groupName);
        if (changed) {
            refreshPlayerCommandTree(playerUuid);
        }
        return changed;
    }

    public boolean revokePlayerGroup(UUID playerUuid, String groupName, eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet contextSet) {
        if (!isInternalPermissionsEnabled()) return false;
        boolean changed = internalPermissionApi.revokeGroup(playerUuid, groupName, contextSet);
        if (changed) {
            refreshPlayerCommandTree(playerUuid);
        }
        return changed;
    }

    public boolean revokePlayerGroupById(UUID playerUuid, String assignmentId) {
        if (!isInternalPermissionsEnabled()) return false;
        boolean changed = internalPermissionApi.revokeGroupById(playerUuid, assignmentId);
        if (changed) refreshPlayerCommandTree(playerUuid);
        return changed;
    }

    public int countPlayerGroupAssignments(UUID playerUuid, String groupName, eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet contextSet) {
        return isInternalPermissionsEnabled() ? internalPermissionApi.countGroupAssignments(playerUuid, groupName, contextSet) : 0;
    }

    public boolean addPermissionToPlayer(UUID playerUuid, String permissionNode, boolean denied) {
        if (!isInternalPermissionsEnabled()) return false;
        boolean changed = internalPermissionApi.addUserPermission(playerUuid, permissionNode, denied);
        if (changed) {
            refreshPlayerCommandTree(playerUuid);
        }
        return changed;
    }

    public boolean addPermissionToPlayer(UUID playerUuid, String permissionNode, boolean denied, eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet contextSet, Long expiresAtMs) {
        if (!isInternalPermissionsEnabled()) return false;
        boolean changed = internalPermissionApi.addUserPermission(playerUuid, permissionNode, denied, contextSet, expiresAtMs);
        if (changed) {
            refreshPlayerCommandTree(playerUuid);
        }
        return changed;
    }

    public boolean removePermissionFromPlayer(UUID playerUuid, String permissionNode) {
        if (!isInternalPermissionsEnabled()) return false;
        boolean changed = internalPermissionApi.removeUserPermission(playerUuid, permissionNode);
        if (changed) {
            refreshPlayerCommandTree(playerUuid);
        }
        return changed;
    }

    public boolean removePermissionFromPlayer(UUID playerUuid, String permissionNode, eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet contextSet) {
        if (!isInternalPermissionsEnabled()) return false;
        boolean changed = internalPermissionApi.removeUserPermission(playerUuid, permissionNode, contextSet);
        if (changed) {
            refreshPlayerCommandTree(playerUuid);
        }
        return changed;
    }

    public boolean removePermissionFromPlayerById(UUID playerUuid, String assignmentId) {
        if (!isInternalPermissionsEnabled()) return false;
        boolean changed = internalPermissionApi.removeUserPermissionById(playerUuid, assignmentId);
        if (changed) refreshPlayerCommandTree(playerUuid);
        return changed;
    }

    public int countPermissionAssignmentsForPlayer(UUID playerUuid, String permissionNode, eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet contextSet) {
        return isInternalPermissionsEnabled() ? internalPermissionApi.countUserPermissionAssignments(playerUuid, permissionNode, contextSet) : 0;
    }

    private void refreshPlayerCommandTree(UUID playerUuid) {
        if (playerUuid == null || platform == null) {
            return;
        }
        try {
            IPlayer online = platform.getPlayerByUuid(playerUuid.toString());
            if (online != null) {
                platform.refreshPlayerCommandTree(online);
            }
        } catch (Throwable t) {
            debugLogger.debugLog("[PermissionsHandler] Failed to refresh command tree for " + playerUuid + ": " + t);
        }
    }

    public java.util.List<String> listPermissionGroups() {
        if (!isInternalPermissionsEnabled()) return java.util.List.of();
        return internalPermissionApi.listGroups();
    }

    public java.util.List<UUID> listPermissionUsers() {
        return isInternalPermissionsEnabled() ? internalPermissionApi.listUserIds() : java.util.List.of();
    }

    public boolean resetInternalPermissionsForMigration() {
        if (!isInternalPermissionsEnabled()) return false;
        internalPermissionApi.resetForMigration();
        if (platform != null) platform.refreshAllPlayerCommandTrees();
        return true;
    }

    public PermissionAPI.GroupInfo getPermissionGroupInfo(String groupName) {
        if (!isInternalPermissionsEnabled()) return null;
        return internalPermissionApi.getGroupInfo(groupName);
    }

    public PermissionAPI.UserGroupsInfo getPlayerGroups(UUID playerUuid) {
        if (!isInternalPermissionsEnabled()) return null;
        return internalPermissionApi.getUserGroups(playerUuid);
    }

    public PermissionAPI.UserInfo getPlayerPermissionInfo(UUID playerUuid) {
        if (!isInternalPermissionsEnabled()) return null;
        return internalPermissionApi.getUserInfo(playerUuid);
    }

    public PermissionAPI.PermissionExplain explainPlayerPermission(UUID playerUuid, String permissionNode) {
        if (!isInternalPermissionsEnabled()) return null;
        return internalPermissionApi.explainPermission(playerUuid, permissionNode);
    }

    public boolean isInternalPermissionsEnabled() {
        try {
            MainConfigHandler.Config cfg = MainConfigHandler.getConfig();
            return cfg != null
                    && cfg.internalPermissionsEnable != null
                    && Boolean.TRUE.equals(cfg.internalPermissionsEnable.value);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public boolean isExternalCommandPermissionsEnabled() {
        try {
            MainConfigHandler.Config cfg = MainConfigHandler.getConfig();
            return cfg != null
                    && cfg.externalCommandPermissionsEnable != null
                    && Boolean.TRUE.equals(cfg.externalCommandPermissionsEnable.value);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public boolean isExternalCommandStrictMode() {
        try {
            MainConfigHandler.Config cfg = MainConfigHandler.getConfig();
            String mode = cfg != null && cfg.externalCommandPermissionMode != null ? cfg.externalCommandPermissionMode.value : null;
            return mode != null && mode.trim().equalsIgnoreCase("strict");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean shouldRegisterForgePermissionHandler() {
        try {
            MainConfigHandler.Config cfg = MainConfigHandler.getConfig();
            return cfg != null
                    && cfg.registerForgePermissionHandler != null
                    && Boolean.TRUE.equals(cfg.registerForgePermissionHandler.value);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public record CommandGuardResult(boolean allowed, String commandLine, String node, String reason) {
        public static CommandGuardResult allowed(String commandLine, String node, String reason) {
            return new CommandGuardResult(true, commandLine, node, reason);
        }

        public static CommandGuardResult denied(String commandLine, String node, String reason) {
            return new CommandGuardResult(false, commandLine, node, reason);
        }
    }
}
