package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.CMConfig;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PermissionsHandler {
    private final Logger logger;
    private final CMConfig cmConfig;
    private final DebugLogger debugLogger;
    private final IPlatformAdapter platform;
    private final Set<UUID> discoveryWarmedUsers = ConcurrentHashMap.newKeySet();

    public static final String MENTION_EVERYONE_PERMISSION = "paradigm.mention.everyone";
    public static final String MENTION_PLAYER_PERMISSION = "paradigm.mention.player";
    public static final String STAFF_CHAT_PERMISSION = "paradigm.staff";
    public static final String RESTART_MANAGE_PERMISSION = "paradigm.restart.manage";
    public static final String BROADCAST_PERMISSION = "paradigm.broadcast";
    public static final String GROUPCHAT_PERMISSION = "paradigm.groupchat";
    public static final String RELOAD_PERMISSION = "paradigm.reload";
    public static final String EDITOR_PERMISSION = "paradigm.editor";
    public static final String PRIVATE_MESSAGE_PERMISSION = "paradigm.msg";
    public static final String PRIVATE_REPLY_PERMISSION = "paradigm.reply";

    public static final int MENTION_EVERYONE_PERMISSION_LEVEL = 2;
    public static final int MENTION_PLAYER_PERMISSION_LEVEL = 0;
    public static final int BROADCAST_PERMISSION_LEVEL = 2;
    public static final int RESTART_MANAGE_PERMISSION_LEVEL = 2;
    public static final int RELOAD_PERMISSION_LEVEL = 2;
    public static final int EDITOR_PERMISSION_LEVEL = 2;
    public static final int PRIVATE_MESSAGE_PERMISSION_LEVEL = 0;
    public static final int PRIVATE_REPLY_PERMISSION_LEVEL = 0;

    public PermissionsHandler(Logger logger, CMConfig cmConfig, DebugLogger debugLogger, IPlatformAdapter platform) {
        this.logger = logger;
        this.cmConfig = cmConfig;
        this.debugLogger = debugLogger;
        this.platform = platform;
    }

    public void initialize() {
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

            for (String permission : allPermissions.keySet()) {
                debugLogger.debugLog("Discovered permission for LuckPerms integration: " + permission);
            }

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

        if (platform == null || platform.getOnlinePlayers() == null) return;

        for (IPlayer onlinePlayer : platform.getOnlinePlayers()) {
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
        registerPermissionsWithLuckPerms();
    }

    public boolean hasPermission(IPlayer player, String permission) {
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
                    boolean lpResult = user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
                    debugLogger.debugLog("[PermissionsHandler] LuckPerms check for '" + permission + "' -> " + lpResult);
                    return lpResult;
                }

                debugLogger.debugLog("[PermissionsHandler] LuckPerms user not cached yet for player: " + player.getName() + ", falling back to vanilla level check.");
            } catch (Throwable t) {
                debugLogger.debugLog("[PermissionsHandler] LuckPerms check failed: " + t);
            }
        }

        int level = vanillaLevelFor(permission);
        debugLogger.debugLog("[PermissionsHandler] Using vanilla fallback for '" + permission + "' with level=" + level);
        try {
            boolean vanillaResult = hasVanillaPermissionLevel(player, level);
            debugLogger.debugLog("[PermissionsHandler] Vanilla check result: " + vanillaResult);
            return vanillaResult;
        } catch (Throwable t) {
            debugLogger.debugLog("[PermissionsHandler] Vanilla check failed: " + t);
        }

        debugLogger.debugLog("[PermissionsHandler] All checks failed for '" + permission + "', returning false");
        return false;
    }

    private static boolean hasVanillaPermissionLevel(IPlayer player, int level) {
        if (player == null) return false;
        Object original = player.getOriginalPlayer();
        if (original == null) return false;

        // Forge/NeoForge mapping style
        try {
            java.lang.reflect.Method m = original.getClass().getMethod("hasPermissions", int.class);
            Object result = m.invoke(original, level);
            if (result instanceof Boolean b) return b;
        } catch (Throwable ignored) {
        }

        // Fabric/Yarn mapping style
        try {
            java.lang.reflect.Method m = original.getClass().getMethod("hasPermissionLevel", int.class);
            Object result = m.invoke(original, level);
            if (result instanceof Boolean b) return b;
        } catch (Throwable ignored) {
        }

        return false;
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
        if (permission.equals(EDITOR_PERMISSION)) return EDITOR_PERMISSION_LEVEL;
        if (permission.equals(PRIVATE_MESSAGE_PERMISSION)) return PRIVATE_MESSAGE_PERMISSION_LEVEL;
        if (permission.equals(PRIVATE_REPLY_PERMISSION)) return PRIVATE_REPLY_PERMISSION_LEVEL;

        return 0;
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
        nodes.put(EDITOR_PERMISSION, "Allows using /paradigm editor and /paradigm apply.");
        nodes.put(PRIVATE_MESSAGE_PERMISSION, "Allows sending private messages with /msg.");
        nodes.put(PRIVATE_REPLY_PERMISSION, "Allows replying to private messages with /reply.");

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

        return nodes;
    }
}
