package eu.avalanche7.paradigm.modules.permissions;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import eu.avalanche7.paradigm.configs.CMConfig;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextResolver;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;

public class PermissionsHandler {
    private final Logger logger;
    private final CMConfig cmConfig;
    private final DebugLogger debugLogger;
    private final IPlatformAdapter platform;
    private final StorageService storageService;
    private final Set<UUID> discoveryWarmedUsers = ConcurrentHashMap.newKeySet();
    private final PermissionAPI internalPermissionApi;
    private final PermissionNodeRegistry permissionNodeRegistry;

    public static final String MENTION_EVERYONE_PERMISSION = ParadigmPermissions.MENTION_EVERYONE.node();
    public static final String MENTION_PLAYER_PERMISSION = ParadigmPermissions.MENTION_PLAYER.node();
    public static final String STAFF_CHAT_PERMISSION = ParadigmPermissions.STAFF_CHAT.node();
    public static final String RESTART_MANAGE_PERMISSION = ParadigmPermissions.RESTART_MANAGE.node();
    public static final String BROADCAST_PERMISSION = ParadigmPermissions.BROADCAST.node();
    public static final String GROUPCHAT_PERMISSION = ParadigmPermissions.GROUP_CHAT.node();
    public static final String RELOAD_PERMISSION = ParadigmPermissions.RELOAD.node();
    public static final String COMMAND_TOGGLE_PERMISSION = ParadigmPermissions.COMMAND_TOGGLE.node();
    public static final String STORAGE_MANAGE_PERMISSION = ParadigmPermissions.STORAGE_MANAGE.node();
    public static final String TABLIST_MANAGE_PERMISSION = ParadigmPermissions.TABLIST_MANAGE.node();
    public static final String GROUP_MANAGE_PERMISSION = ParadigmPermissions.GROUP_MANAGE.node();
    public static final String PRIVATE_MESSAGE_PERMISSION = ParadigmPermissions.PRIVATE_MESSAGE.node();
    public static final String PRIVATE_REPLY_PERMISSION = ParadigmPermissions.PRIVATE_REPLY.node();
    public static final String SOCIALSPY_PERMISSION = ParadigmPermissions.SOCIAL_SPY.node();
    public static final String SPAWN_PERMISSION = ParadigmPermissions.SPAWN.node();
    public static final String SETSPAWN_PERMISSION = ParadigmPermissions.SET_SPAWN.node();
    public static final String SEEN_PERMISSION = ParadigmPermissions.SEEN.node();
    public static final String IGNORE_PERMISSION = ParadigmPermissions.IGNORE.node();
    public static final String GAMEMODE_PERMISSION = ParadigmPermissions.GAMEMODE.node();
    public static final String GAMEMODE_OTHERS_PERMISSION = ParadigmPermissions.GAMEMODE_OTHERS.node();
    public static final String FLY_PERMISSION = ParadigmPermissions.FLY.node();
    public static final String FLY_OTHERS_PERMISSION = ParadigmPermissions.FLY_OTHERS.node();
    public static final String CLEARINV_PERMISSION = ParadigmPermissions.CLEAR_INVENTORY.node();
    public static final String CLEARINV_OTHERS_PERMISSION = ParadigmPermissions.CLEAR_INVENTORY_OTHERS.node();
    public static final String TIME_PERMISSION = ParadigmPermissions.TIME.node();
    public static final String WEATHER_PERMISSION = ParadigmPermissions.WEATHER.node();
    public static final String SPEED_PERMISSION = ParadigmPermissions.SPEED.node();
    public static final String SPEED_OTHERS_PERMISSION = ParadigmPermissions.SPEED_OTHERS.node();
    public static final String FEED_PERMISSION = ParadigmPermissions.FEED.node();
    public static final String FEED_OTHERS_PERMISSION = ParadigmPermissions.FEED_OTHERS.node();
    public static final String HEAL_PERMISSION = ParadigmPermissions.HEAL.node();
    public static final String HEAL_OTHERS_PERMISSION = ParadigmPermissions.HEAL_OTHERS.node();
    public static final String HOME_USE_PERMISSION = ParadigmPermissions.HOME_USE.node();
    public static final String HOME_SET_PERMISSION = ParadigmPermissions.HOME_SET.node();
    public static final String HOME_DEL_PERMISSION = ParadigmPermissions.HOME_DELETE.node();
    public static final String HOME_LIST_PERMISSION = ParadigmPermissions.HOME_LIST.node();
    public static final String BACK_PERMISSION = ParadigmPermissions.BACK.node();
    public static final String TPA_PERMISSION = ParadigmPermissions.TPA.node();
    public static final String TPAHERE_PERMISSION = ParadigmPermissions.TPA_HERE.node();
    public static final String TPACCEPT_PERMISSION = ParadigmPermissions.TPA_ACCEPT.node();
    public static final String TPDENY_PERMISSION = ParadigmPermissions.TPA_DENY.node();
    public static final String TPCANCEL_PERMISSION = ParadigmPermissions.TPA_CANCEL.node();
    public static final String WARP_USE_PERMISSION = ParadigmPermissions.WARP_USE.node();
    public static final String WARP_WILDCARD_PERMISSION = ParadigmPermissions.WARP_WILDCARD.node();
    public static final String WARP_SET_PERMISSION = ParadigmPermissions.WARP_SET.node();
    public static final String WARP_DELETE_PERMISSION = ParadigmPermissions.WARP_DELETE.node();
    public static final String WARP_LIST_PERMISSION = ParadigmPermissions.WARP_LIST.node();
    public static final String WARP_INFO_PERMISSION = ParadigmPermissions.WARP_INFO.node();
    public static final String KICK_PERMISSION = ParadigmPermissions.KICK.node();
    public static final String BAN_PERMISSION = ParadigmPermissions.BAN.node();
    public static final String TEMPBAN_PERMISSION = ParadigmPermissions.TEMP_BAN.node();
    public static final String IPBAN_PERMISSION = ParadigmPermissions.IP_BAN.node();
    public static final String MUTE_PERMISSION = ParadigmPermissions.MUTE.node();
    public static final String TEMPMUTE_PERMISSION = ParadigmPermissions.TEMP_MUTE.node();
    public static final String WARN_PERMISSION = ParadigmPermissions.WARN.node();
    public static final String JAIL_PERMISSION = ParadigmPermissions.JAIL.node();
    public static final String JAIL_MANAGE_PERMISSION = ParadigmPermissions.JAIL_MANAGE.node();
    public static final String DASHBOARD_MANAGE_PERMISSION = ParadigmPermissions.DASHBOARD_MANAGE.node();
    public static final String VANISH_PERMISSION = ParadigmPermissions.VANISH.node();
    public static final String VANISH_OTHERS_PERMISSION = ParadigmPermissions.VANISH_OTHERS.node();
    public static final String GOD_PERMISSION = ParadigmPermissions.GOD.node();
    public static final String GOD_OTHERS_PERMISSION = ParadigmPermissions.GOD_OTHERS.node();
    public static final String INVSEE_PERMISSION = ParadigmPermissions.INVENTORY_SEE.node();
    public static final String ENDERSEE_PERMISSION = ParadigmPermissions.ENDER_SEE.node();
    public static final String REPAIR_PERMISSION = ParadigmPermissions.REPAIR.node();
    public static final String REPAIR_OTHERS_PERMISSION = ParadigmPermissions.REPAIR_OTHERS.node();
    public static final String ENCHANT_PERMISSION = ParadigmPermissions.ENCHANT.node();
    public static final String ENCHANT_OTHERS_PERMISSION = ParadigmPermissions.ENCHANT_OTHERS.node();
    public static final String SUDO_PERMISSION = ParadigmPermissions.SUDO.node();
    public static final String NEAR_PERMISSION = ParadigmPermissions.NEAR.node();
    public static final String WHOIS_PERMISSION = ParadigmPermissions.WHOIS.node();
    public static final String TOP_PERMISSION = ParadigmPermissions.TOP.node();
    public static final String JUMP_PERMISSION = ParadigmPermissions.JUMP.node();
    public static final String RTP_PERMISSION = ParadigmPermissions.RTP.node();
    public static final String HOME_LIMIT_PERMISSION_PREFIX = ParadigmPermissions.HOME_LIMIT_PREFIX;
    public static final String HOME_LIMIT_UNLIMITED_PERMISSION = ParadigmPermissions.HOME_LIMIT_UNLIMITED.node();

    public static final int MENTION_EVERYONE_PERMISSION_LEVEL = ParadigmPermissions.MENTION_EVERYONE.fallbackLevel();
    public static final int MENTION_PLAYER_PERMISSION_LEVEL = ParadigmPermissions.MENTION_PLAYER.fallbackLevel();
    public static final int RESTART_MANAGE_PERMISSION_LEVEL = ParadigmPermissions.RESTART_MANAGE.fallbackLevel();
    public static final int BROADCAST_PERMISSION_LEVEL = ParadigmPermissions.BROADCAST.fallbackLevel();
    public static final int RELOAD_PERMISSION_LEVEL = ParadigmPermissions.RELOAD.fallbackLevel();
    public static final int COMMAND_TOGGLE_PERMISSION_LEVEL = ParadigmPermissions.COMMAND_TOGGLE.fallbackLevel();
    public static final int STORAGE_MANAGE_PERMISSION_LEVEL = ParadigmPermissions.STORAGE_MANAGE.fallbackLevel();
    public static final int TABLIST_MANAGE_PERMISSION_LEVEL = ParadigmPermissions.TABLIST_MANAGE.fallbackLevel();
    public static final int GROUP_MANAGE_PERMISSION_LEVEL = ParadigmPermissions.GROUP_MANAGE.fallbackLevel();
    public static final int PRIVATE_MESSAGE_PERMISSION_LEVEL = ParadigmPermissions.PRIVATE_MESSAGE.fallbackLevel();
    public static final int PRIVATE_REPLY_PERMISSION_LEVEL = ParadigmPermissions.PRIVATE_REPLY.fallbackLevel();
    public static final int SOCIALSPY_PERMISSION_LEVEL = ParadigmPermissions.SOCIAL_SPY.fallbackLevel();
    public static final int SPAWN_PERMISSION_LEVEL = ParadigmPermissions.SPAWN.fallbackLevel();
    public static final int SETSPAWN_PERMISSION_LEVEL = ParadigmPermissions.SET_SPAWN.fallbackLevel();
    public static final int SEEN_PERMISSION_LEVEL = ParadigmPermissions.SEEN.fallbackLevel();
    public static final int IGNORE_PERMISSION_LEVEL = ParadigmPermissions.IGNORE.fallbackLevel();
    public static final int GAMEMODE_PERMISSION_LEVEL = ParadigmPermissions.GAMEMODE.fallbackLevel();
    public static final int GAMEMODE_OTHERS_PERMISSION_LEVEL = ParadigmPermissions.GAMEMODE_OTHERS.fallbackLevel();
    public static final int FLY_PERMISSION_LEVEL = ParadigmPermissions.FLY.fallbackLevel();
    public static final int FLY_OTHERS_PERMISSION_LEVEL = ParadigmPermissions.FLY_OTHERS.fallbackLevel();
    public static final int CLEARINV_PERMISSION_LEVEL = ParadigmPermissions.CLEAR_INVENTORY.fallbackLevel();
    public static final int CLEARINV_OTHERS_PERMISSION_LEVEL = ParadigmPermissions.CLEAR_INVENTORY_OTHERS.fallbackLevel();
    public static final int TIME_PERMISSION_LEVEL = ParadigmPermissions.TIME.fallbackLevel();
    public static final int WEATHER_PERMISSION_LEVEL = ParadigmPermissions.WEATHER.fallbackLevel();
    public static final int SPEED_PERMISSION_LEVEL = ParadigmPermissions.SPEED.fallbackLevel();
    public static final int SPEED_OTHERS_PERMISSION_LEVEL = ParadigmPermissions.SPEED_OTHERS.fallbackLevel();
    public static final int FEED_PERMISSION_LEVEL = ParadigmPermissions.FEED.fallbackLevel();
    public static final int FEED_OTHERS_PERMISSION_LEVEL = ParadigmPermissions.FEED_OTHERS.fallbackLevel();
    public static final int HEAL_PERMISSION_LEVEL = ParadigmPermissions.HEAL.fallbackLevel();
    public static final int HEAL_OTHERS_PERMISSION_LEVEL = ParadigmPermissions.HEAL_OTHERS.fallbackLevel();
    public static final int HOME_USE_PERMISSION_LEVEL = ParadigmPermissions.HOME_USE.fallbackLevel();
    public static final int HOME_SET_PERMISSION_LEVEL = ParadigmPermissions.HOME_SET.fallbackLevel();
    public static final int HOME_DEL_PERMISSION_LEVEL = ParadigmPermissions.HOME_DELETE.fallbackLevel();
    public static final int HOME_LIST_PERMISSION_LEVEL = ParadigmPermissions.HOME_LIST.fallbackLevel();
    public static final int BACK_PERMISSION_LEVEL = ParadigmPermissions.BACK.fallbackLevel();
    public static final int TPA_PERMISSION_LEVEL = ParadigmPermissions.TPA.fallbackLevel();
    public static final int TPAHERE_PERMISSION_LEVEL = ParadigmPermissions.TPA_HERE.fallbackLevel();
    public static final int TPACCEPT_PERMISSION_LEVEL = ParadigmPermissions.TPA_ACCEPT.fallbackLevel();
    public static final int TPDENY_PERMISSION_LEVEL = ParadigmPermissions.TPA_DENY.fallbackLevel();
    public static final int TPCANCEL_PERMISSION_LEVEL = ParadigmPermissions.TPA_CANCEL.fallbackLevel();
    public static final int WARP_USE_PERMISSION_LEVEL = ParadigmPermissions.WARP_USE.fallbackLevel();
    public static final int WARP_WILDCARD_PERMISSION_LEVEL = ParadigmPermissions.WARP_WILDCARD.fallbackLevel();
    public static final int WARP_SET_PERMISSION_LEVEL = ParadigmPermissions.WARP_SET.fallbackLevel();
    public static final int WARP_DELETE_PERMISSION_LEVEL = ParadigmPermissions.WARP_DELETE.fallbackLevel();
    public static final int WARP_LIST_PERMISSION_LEVEL = ParadigmPermissions.WARP_LIST.fallbackLevel();
    public static final int WARP_INFO_PERMISSION_LEVEL = ParadigmPermissions.WARP_INFO.fallbackLevel();
    public static final int KICK_PERMISSION_LEVEL = ParadigmPermissions.KICK.fallbackLevel();
    public static final int BAN_PERMISSION_LEVEL = ParadigmPermissions.BAN.fallbackLevel();
    public static final int TEMPBAN_PERMISSION_LEVEL = ParadigmPermissions.TEMP_BAN.fallbackLevel();
    public static final int MUTE_PERMISSION_LEVEL = ParadigmPermissions.MUTE.fallbackLevel();
    public static final int TEMPMUTE_PERMISSION_LEVEL = ParadigmPermissions.TEMP_MUTE.fallbackLevel();
    public static final int WARN_PERMISSION_LEVEL = ParadigmPermissions.WARN.fallbackLevel();
    public static final int JAIL_PERMISSION_LEVEL = ParadigmPermissions.JAIL.fallbackLevel();
    public static final int JAIL_MANAGE_PERMISSION_LEVEL = ParadigmPermissions.JAIL_MANAGE.fallbackLevel();
    public static final int DASHBOARD_MANAGE_PERMISSION_LEVEL = ParadigmPermissions.DASHBOARD_MANAGE.fallbackLevel();
    public static final int VANISH_PERMISSION_LEVEL = ParadigmPermissions.VANISH.fallbackLevel();
    public static final int VANISH_OTHERS_PERMISSION_LEVEL = ParadigmPermissions.VANISH_OTHERS.fallbackLevel();
    public static final int GOD_PERMISSION_LEVEL = ParadigmPermissions.GOD.fallbackLevel();
    public static final int GOD_OTHERS_PERMISSION_LEVEL = ParadigmPermissions.GOD_OTHERS.fallbackLevel();
    public static final int INVSEE_PERMISSION_LEVEL = ParadigmPermissions.INVENTORY_SEE.fallbackLevel();
    public static final int ENDERSEE_PERMISSION_LEVEL = ParadigmPermissions.ENDER_SEE.fallbackLevel();
    public static final int REPAIR_PERMISSION_LEVEL = ParadigmPermissions.REPAIR.fallbackLevel();
    public static final int REPAIR_OTHERS_PERMISSION_LEVEL = ParadigmPermissions.REPAIR_OTHERS.fallbackLevel();
    public static final int ENCHANT_PERMISSION_LEVEL = ParadigmPermissions.ENCHANT.fallbackLevel();
    public static final int ENCHANT_OTHERS_PERMISSION_LEVEL = ParadigmPermissions.ENCHANT_OTHERS.fallbackLevel();
    public static final int SUDO_PERMISSION_LEVEL = ParadigmPermissions.SUDO.fallbackLevel();
    public static final int NEAR_PERMISSION_LEVEL = ParadigmPermissions.NEAR.fallbackLevel();
    public static final int WHOIS_PERMISSION_LEVEL = ParadigmPermissions.WHOIS.fallbackLevel();
    public static final int TOP_PERMISSION_LEVEL = ParadigmPermissions.TOP.fallbackLevel();
    public static final int JUMP_PERMISSION_LEVEL = ParadigmPermissions.JUMP.fallbackLevel();
    public static final int RTP_PERMISSION_LEVEL = ParadigmPermissions.RTP.fallbackLevel();

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
        } catch (ClassNotFoundException | LinkageError absent) {
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

        if (platform == null || platform.getMinecraftServer() == null) return;

        List<IPlayer> onlinePlayers = platform.getOnlinePlayers();
        if (onlinePlayers == null) return;

        for (IPlayer onlinePlayer : onlinePlayers) {
            if (onlinePlayer == null || onlinePlayer.getUUID() == null) continue;

            UUID uuid;
            try {
                uuid = UUID.fromString(onlinePlayer.getUUID());
            } catch (IllegalArgumentException malformedUuid) {
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
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    private void warmupUserPermissions(net.luckperms.api.model.user.User user, Map<String, String> allPermissions) {
        if (user == null || allPermissions == null || allPermissions.isEmpty()) return;

        for (String permission : allPermissions.keySet()) {
            try {
                user.getCachedData().getPermissionData().checkPermission(permission);
            } catch (RuntimeException | LinkageError ignored) {
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
            } catch (RuntimeException | LinkageError t) {
                debugLogger.debugLog("[PermissionsHandler] LuckPerms explicit query failed for '" + permission + "': " + t);
            }
        }

        if (isInternalPermissionsEnabled()) {
            try {
                return internalPermissionApi.hasPermission(player, permission);
            } catch (RuntimeException t) {
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
            } catch (RuntimeException | LinkageError t) {
                debugLogger.debugLog("[PermissionsHandler] LuckPerms UUID explicit query failed for '" + permission + "': " + t);
            }
        }

        if (isInternalPermissionsEnabled()) {
            try {
                return internalPermissionApi.hasPermission(playerUuid, permission);
            } catch (RuntimeException t) {
                debugLogger.debugLog("[PermissionsHandler] Internal UUID explicit query failed for '" + permission + "': " + t);
            }
        }

        return null;
    }

    public Boolean queryDefinedPermission(UUID playerUuid, String permission, PermissionContextSet context) {
        if (playerUuid == null || permission == null || permission.isBlank()) return null;
        PermissionContextSet effectiveContext = context != null ? context : PermissionContextSet.empty();

        if (isLuckPermsAvailable()) {
            try {
                Boolean result = LuckPermsPublicApiBridge.query(playerUuid, permission, effectiveContext);
                if (result != null) return result;
            } catch (RuntimeException | LinkageError t) {
                debugLogger.debugLog("[PermissionsHandler] LuckPerms contextual query failed for '" + permission + "': " + t);
            }
        }

        if (isInternalPermissionsEnabled()) {
            try {
                return internalPermissionApi.hasPermission(playerUuid, permission, effectiveContext);
            } catch (RuntimeException t) {
                debugLogger.debugLog("[PermissionsHandler] Internal contextual query failed for '" + permission + "': " + t);
            }
        }
        return null;
    }

    public boolean hasPermission(UUID playerUuid, String permission, int fallbackLevel, PermissionContextSet context) {
        if (playerUuid == null || permission == null || permission.isBlank()) return false;
        Boolean defined = queryDefinedPermission(playerUuid, permission, context);
        if (defined != null) return defined;
        if (fallbackLevel < 0 || platform == null) return false;
        IPlayer online = platform.getPlayerByUuid(playerUuid.toString());
        if (online == null) return false;
        try {
            return platform.hasPermission(online, permission, fallbackLevel);
        } catch (RuntimeException failure) {
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

    public boolean hasPermission(IPlayer player, PermissionDefinition permission) {
        return permission != null && hasPermission(player, permission.node(), permission.fallbackLevel());
    }

    public boolean hasPermission(String playerUuid, PermissionDefinition permission) {
        return permission != null && hasPermission(playerUuid, permission.node(), permission.fallbackLevel());
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
            } catch (RuntimeException | LinkageError t) {
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
            boolean vanillaResult = platform.hasVanillaPermissionLevel(player, vanillaLevelFallback);
            debugLogger.debugLog("[PermissionsHandler] Vanilla check result: " + vanillaResult);
            return vanillaResult;
        } catch (RuntimeException | LinkageError t) {
            debugLogger.debugLog("[PermissionsHandler] Vanilla check failed: " + t);
        }

        debugLogger.debugLog("[PermissionsHandler] All checks failed for '" + permission + "', returning false");
        return false;
    }

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
            } catch (RuntimeException | LinkageError ignored) { }
        }
        if (isInternalPermissionsEnabled()) {
            Boolean result = internalPermissionApi.hasPermission(uuid, permission);
            if (result != null) return result;
        }
        return false;
    }

    private boolean hasOperatorBypass(IPlayer player) {
        try {

            return platform.hasVanillaPermissionLevel(player, 2);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public boolean hasStrictVanillaPermissionLevel(IPlayer player, int level) {
        return platform.hasVanillaPermissionLevel(player, level);
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
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Method findMethod(Class<?> type, String name, Class<?>... params) {
        if (type == null || name == null || name.isBlank()) {
            return null;
        }

        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
        }

        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
            }
        }
        return null;
    }

    private static int vanillaLevelFor(String permission) {
        return ParadigmPermissions.fallbackLevelOf(permission);
    }

    public Map<String, String> knownPermissionNodes() {
        Map<String, String> nodes = ParadigmPermissions.descriptions();

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

    public PermissionAPI.PermissionMeta resolvePlayerMetadata(IPlayer player) {
        if (!isInternalPermissionsEnabled()) return null;
        return internalPermissionApi.resolveMeta(player);
    }

    public long permissionsStateVersion() {
        return internalPermissionApi.stateVersion();
    }

    public PermissionAPI.PermissionMeta resolvePlayerMetadata(UUID playerUuid) {
        if (playerUuid == null) return null;
        if (isInternalPermissionsEnabled()) return internalPermissionApi.resolveMeta(playerUuid);
        if (!isLuckPermsAvailable()) return null;
        try {
            return LuckPermsPublicApiBridge.metadata(playerUuid);
        } catch (RuntimeException | LinkageError failure) {
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
        } catch (RuntimeException t) {
            debugLogger.debugLog("[PermissionsHandler] Failed to refresh command tree for " + playerUuid + ": " + t);
        }
    }

    public java.util.List<String> listPermissionGroups() {
        if (!isInternalPermissionsEnabled()) return java.util.List.of();
        return internalPermissionApi.listGroups();
    }

    public java.util.List<eu.avalanche7.paradigm.storage.model.StoredPermissionTrack> listPermissionTracks() {
        return isInternalPermissionsEnabled() ? internalPermissionApi.listTracks() : java.util.List.of();
    }

    public eu.avalanche7.paradigm.storage.model.StoredPermissionTrack getPermissionTrack(String track) {
        return isInternalPermissionsEnabled() ? internalPermissionApi.getTrack(track) : null;
    }

    public PermissionTrackResult mutatePermissionTrack(String action, String track, String group, String target, Integer position) {
        if (!isInternalPermissionsEnabled()) return PermissionTrackResult.of(false, "storage_unavailable", "Internal permissions are disabled.", track, null, null);
        return switch (action) {
            case "track_create" -> internalPermissionApi.createTrack(track);
            case "track_delete" -> internalPermissionApi.deleteTrack(track);
            case "track_rename" -> internalPermissionApi.renameTrack(track, target);
            case "track_clone" -> internalPermissionApi.cloneTrack(track, target);
            case "track_clear" -> internalPermissionApi.clearTrack(track);
            case "track_append" -> internalPermissionApi.appendTrackGroup(track, group);
            case "track_insert" -> internalPermissionApi.insertTrackGroup(track, group, position != null ? position : 0);
            case "track_remove" -> internalPermissionApi.removeTrackGroup(track, group);
            case "track_move" -> internalPermissionApi.moveTrackGroup(track, group, position != null ? position : 0);
            default -> PermissionTrackResult.of(false, "invalid_position", "Unknown track operation.", track, null, null);
        };
    }

    public PermissionTrackResult movePlayerOnTrack(UUID player, String track, PermissionContextSet contexts, String operation,
                                                    boolean dontAddToFirst, boolean dontRemoveFromFirst, Long expiry,
                                                    String actor, String targetGroup) {
        return movePlayerOnTrack(player, track, contexts, operation, dontAddToFirst, dontRemoveFromFirst, expiry,
                expiry != null, actor, targetGroup);
    }

    public PermissionTrackResult movePlayerOnTrack(UUID player, String track, PermissionContextSet contexts, String operation,
                                                    boolean dontAddToFirst, boolean dontRemoveFromFirst, Long expiry,
                                                    boolean expiryRequested, String actor, String targetGroup) {
        if (!isInternalPermissionsEnabled()) return PermissionTrackResult.of(false, "storage_unavailable", "Internal permissions are disabled.", track, null, null);
        PermissionTrackResult result = internalPermissionApi.moveUserOnTrack(player, track, contexts, operation,
                dontAddToFirst, dontRemoveFromFirst, expiry, expiryRequested, actor, targetGroup);
        if (result.applied()) refreshPlayerCommandTree(player);
        return result;
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
        } catch (RuntimeException configUnavailable) {
            return true;
        }
    }

    public boolean isExternalCommandPermissionsEnabled() {
        try {
            MainConfigHandler.Config cfg = MainConfigHandler.getConfig();
            return cfg != null
                    && cfg.externalCommandPermissionsEnable != null
                    && Boolean.TRUE.equals(cfg.externalCommandPermissionsEnable.value);
        } catch (RuntimeException configUnavailable) {
            return true;
        }
    }

    public boolean isExternalCommandStrictMode() {
        try {
            MainConfigHandler.Config cfg = MainConfigHandler.getConfig();
            String mode = cfg != null && cfg.externalCommandPermissionMode != null ? cfg.externalCommandPermissionMode.value : null;
            return mode != null && mode.trim().equalsIgnoreCase("strict");
        } catch (RuntimeException configUnavailable) {
            return false;
        }
    }

    public boolean shouldRegisterForgePermissionHandler() {
        try {
            MainConfigHandler.Config cfg = MainConfigHandler.getConfig();
            return cfg != null
                    && cfg.registerForgePermissionHandler != null
                    && Boolean.TRUE.equals(cfg.registerForgePermissionHandler.value);
        } catch (RuntimeException configUnavailable) {
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
