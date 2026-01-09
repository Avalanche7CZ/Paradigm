package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.CMConfig;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

public class PermissionsHandler {
    private final Logger logger;
    private final CMConfig cmConfig;
    private final DebugLogger debugLogger;
    private final IPlatformAdapter platform;

    public static final String MENTION_EVERYONE_PERMISSION = "paradigm.mention.everyone";
    public static final String MENTION_PLAYER_PERMISSION = "paradigm.mention.player";
    public static final String STAFF_CHAT_PERMISSION = "paradigm.staff";
    public static final String RESTART_MANAGE_PERMISSION = "paradigm.restart.manage";
    public static final String BROADCAST_PERMISSION = "paradigm.broadcast";
    public static final String GROUPCHAT_PERMISSION = "paradigm.groupchat";
    public static final String RELOAD_PERMISSION = "paradigm.reload";

    public static final int MENTION_EVERYONE_PERMISSION_LEVEL = 2;
    public static final int MENTION_PLAYER_PERMISSION_LEVEL = 0;
    public static final int BROADCAST_PERMISSION_LEVEL = 2;
    public static final int RESTART_MANAGE_PERMISSION_LEVEL = 2;

    public PermissionsHandler(Logger logger, CMConfig cmConfig, DebugLogger debugLogger, IPlatformAdapter platform) {
        this.logger = logger;
        this.cmConfig = cmConfig;
        this.debugLogger = debugLogger;
        this.platform = platform;
    }

    public void initialize() {
        // no-op: platform decides how permissions work
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

            java.util.UUID dummyUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");
            net.luckperms.api.model.user.User dummyUser = api.getUserManager().loadUser(dummyUuid).join();

            if (dummyUser != null) {
                for (String permission : allPermissions.keySet()) {
                    dummyUser.getCachedData().getPermissionData().checkPermission(permission);
                    debugLogger.debugLog("Registered permission with LuckPerms: " + permission);
                }
                logger.info("Paradigm: Made {} permissions visible to LuckPerms.", allPermissions.size());
            }
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

    private void scheduleRetry(int attemptCount) {
        new Thread(() -> {
            try {
                Thread.sleep((attemptCount + 1) * 1000L);
                registerPermissionsWithLuckPermsRetry(attemptCount + 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
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
                    boolean lpResult = user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
                    debugLogger.debugLog("[PermissionsHandler] LuckPerms check for '" + permission + "' -> " + lpResult);
                    return lpResult;
                }

                user = api.getUserManager().loadUser(uuid).join();
                if (user != null) {
                    boolean lpResult = user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
                    debugLogger.debugLog("[PermissionsHandler] LuckPerms (loaded) check for '" + permission + "' -> " + lpResult);
                    return lpResult;
                }
            } catch (Throwable t) {
                debugLogger.debugLog("[PermissionsHandler] LuckPerms check failed: " + t.toString());
            }
        }

        if (platform != null) {
            int level = vanillaLevelFor(permission);
            debugLogger.debugLog("[PermissionsHandler] Using vanilla fallback for '" + permission + "' with level=" + level);
            try {
                boolean vanillaResult = platform.hasPermission(player, permission, level);
                debugLogger.debugLog("[PermissionsHandler] Vanilla check result: " + vanillaResult);
                return vanillaResult;
            } catch (Throwable t) {
                debugLogger.debugLog("[PermissionsHandler] Vanilla check failed: " + t.toString());
            }
        }

        debugLogger.debugLog("[PermissionsHandler] All checks failed for '" + permission + "', returning false");
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
        if (permission.equals(RELOAD_PERMISSION)) return 2;

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
