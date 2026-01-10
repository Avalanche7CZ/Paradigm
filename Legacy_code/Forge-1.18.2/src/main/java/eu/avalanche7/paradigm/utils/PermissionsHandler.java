package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.CMConfig;
import eu.avalanche7.paradigm.data.CustomCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

public class PermissionsHandler {
    private final Logger logger;
    private final CMConfig cmConfig;
    private final DebugLogger debugLogger;

    public static final String MENTION_EVERYONE_PERMISSION = "paradigm.mention.everyone";
    public static final String MENTION_PLAYER_PERMISSION = "paradigm.mention.player";
    public static final String STAFF_CHAT_PERMISSION = "paradigm.staff";
    public static final String RESTART_MANAGE_PERMISSION = "paradigm.restart.manage";
    public static final String BROADCAST_PERMISSION = "paradigm.broadcast";
    public static final String ACTIONBAR_PERMISSION = "paradigm.actionbar";
    public static final String TITLE_PERMISSION = "paradigm.title";
    public static final String BOSSBAR_PERMISSION = "paradigm.bossbar";
    public static final String RELOAD_PERMISSION = "paradigm.reload";
    public static final String GROUPCHAT_PERMISSION = "paradigm.groupchat";

    public static final int MENTION_EVERYONE_PERMISSION_LEVEL = 2;
    public static final int MENTION_PLAYER_PERMISSION_LEVEL = 0;
    public static final int STAFF_CHAT_PERMISSION_LEVEL = 2;
    public static final int RESTART_MANAGE_PERMISSION_LEVEL = 2;
    public static final int BROADCAST_PERMISSION_LEVEL = 2;
    public static final int ACTIONBAR_PERMISSION_LEVEL = 2;
    public static final int TITLE_PERMISSION_LEVEL = 2;
    public static final int BOSSBAR_PERMISSION_LEVEL = 2;
    public static final int RELOAD_PERMISSION_LEVEL = 2;

    private PermissionChecker checker;

    public PermissionsHandler(Logger logger, CMConfig cmConfig, DebugLogger debugLogger) {
        this.logger = logger;
        this.cmConfig = cmConfig;
        this.debugLogger = debugLogger;
    }

    public void initialize() {
        initializeChecker();
        registerLuckPermsPermissions();
    }

    public void refreshCustomCommandPermissions() {
        registerLuckPermsPermissions();
    }

    public Map<String, String> knownPermissionNodes() {
        Map<String, String> nodes = new LinkedHashMap<>();

        nodes.put(STAFF_CHAT_PERMISSION, "Access to /sc (Staff Chat) and receiving staff messages.");
        nodes.put(MENTION_EVERYONE_PERMISSION, "Allows using @everyone to ping all players in Mentions module.");
        nodes.put(MENTION_PLAYER_PERMISSION, "Allows mentioning individual players in Mentions module.");
        nodes.put(RESTART_MANAGE_PERMISSION, "Allows managing restarts: /restart now, /restart cancel.");
        nodes.put(BROADCAST_PERMISSION, "Allows using /paradigm broadcast, actionbar, title, and bossbar commands.");
        nodes.put(ACTIONBAR_PERMISSION, "Allows sending actionbar messages via /paradigm actionbar.");
        nodes.put(TITLE_PERMISSION, "Allows sending titles via /paradigm title.");
        nodes.put(BOSSBAR_PERMISSION, "Allows sending bossbar messages via /paradigm bossbar.");
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

    private void registerLuckPermsPermissions() {
        if (!ModList.get().isLoaded("luckperms")) {
            return;
        }
        registerPermissionsWithLuckPermsRetry(0);
    }

    private void registerPermissionsWithLuckPermsRetry(int attemptCount) {
        if (attemptCount >= 5) {
            logger.warn("Paradigm: Failed to register permissions with LuckPerms after {} attempts.", attemptCount);
            return;
        }
        try {
            net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
            Map<String, String> all = knownPermissionNodes();
            java.util.UUID dummyUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");
            net.luckperms.api.model.user.User dummyUser = api.getUserManager().loadUser(dummyUuid).join();
            if (dummyUser != null) {
                for (String node : all.keySet()) {
                    dummyUser.getCachedData().getPermissionData().checkPermission(node);
                    debugLogger.debugLog("Registered permission with LuckPerms: " + node);
                }
                logger.info("Paradigm: Made {} permissions visible to LuckPerms.", all.size());
            }
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("API isn't loaded")) {
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
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "Paradigm-LPReg-Retry").start();
    }

    private void initializeChecker() {
        if (checker == null) {
            if (ModList.get().isLoaded("luckperms")) {
                this.checker = new LuckPermsCheckerImpl();
                logger.info("Paradigm: Using LuckPerms for permission checks.");
            } else {
                this.checker = new VanillaPermissionCheckerImpl();
                logger.info("Paradigm: LuckPerms not found. Using vanilla operator permissions for checks.");
            }
        }
    }

    public boolean hasPermission(ServerPlayer player, String permission) {
        if (player == null) return false;
        if (checker == null) {
            logger.warn("PermissionsHandler: Checker not initialized. Attempting first-time initialization.");
            initialize();
        }
        return checker.hasPermission(player, permission);
    }

    /**
     * Checks if the player has permission to use the reload command.
     */
    public boolean hasReloadPermission(ServerPlayer player) {
        return hasPermission(player, RELOAD_PERMISSION);
    }

    public interface PermissionChecker {
        boolean hasPermission(ServerPlayer player, String permission);
    }

    /**
     * Checks permissions using the LuckPerms API.
     */
    public static class LuckPermsCheckerImpl implements PermissionChecker {
        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            try {
                net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
                net.luckperms.api.model.user.User user = api.getUserManager().getUser(player.getUUID());
                if (user != null) {
                    return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
                }
            } catch (Exception e) {
                return false;
            }
            return false;
        }
    }

    /**
     * Checks permissions using Minecraft's built-in operator levels as a fallback.
     */
    private static class VanillaPermissionCheckerImpl implements PermissionChecker {
        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            int requiredLevel = getPermissionLevelForVanilla(permission);
            return player.hasPermissions(requiredLevel);
        }

        private int getPermissionLevelForVanilla(String permission) {
            if (permission == null) return 4;
            return switch (permission) {
                case STAFF_CHAT_PERMISSION -> STAFF_CHAT_PERMISSION_LEVEL;
                case MENTION_EVERYONE_PERMISSION -> MENTION_EVERYONE_PERMISSION_LEVEL;
                case MENTION_PLAYER_PERMISSION -> MENTION_PLAYER_PERMISSION_LEVEL;
                case BROADCAST_PERMISSION -> BROADCAST_PERMISSION_LEVEL;
                case ACTIONBAR_PERMISSION -> ACTIONBAR_PERMISSION_LEVEL;
                case TITLE_PERMISSION -> TITLE_PERMISSION_LEVEL;
                case BOSSBAR_PERMISSION -> BOSSBAR_PERMISSION_LEVEL;
                case RESTART_MANAGE_PERMISSION -> RESTART_MANAGE_PERMISSION_LEVEL;
                case RELOAD_PERMISSION -> RELOAD_PERMISSION_LEVEL;
                case GROUPCHAT_PERMISSION -> 0;
                default -> permission.startsWith("paradigm.") ? 0 : 4;
            };
        }
    }
}

