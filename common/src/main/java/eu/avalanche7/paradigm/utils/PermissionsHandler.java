package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.CMConfig;
import eu.avalanche7.paradigm.data.CustomCommand;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
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
    public static final String GROUPCHAT_PERMISSION = "paradigm.groupchat";
    public static final String RELOAD_PERMISSION = "paradigm.reload";

    public static final int MENTION_EVERYONE_PERMISSION_LEVEL = 2;
    public static final int MENTION_PLAYER_PERMISSION_LEVEL = 0;
    public static final int BROADCAST_PERMISSION_LEVEL = 2;
    public static final int RESTART_MANAGE_PERMISSION_LEVEL = 2;

    private PermissionChecker checker;

    public PermissionsHandler(Logger logger, CMConfig cmConfig, DebugLogger debugLogger) {
        this.logger = logger;
        this.cmConfig = cmConfig;
        this.debugLogger = debugLogger;
    }

    public void initialize() {
        initializeChecker();
    }

    public void registerLuckPermsPermissions() {
        registerPermissionsWithLuckPerms();
    }

    private void initializeChecker() {
        if (checker == null) {
            if (FabricLoader.getInstance().isModLoaded("luckperms")) {
                this.checker = new LuckPermsCheckerImpl();
                logger.info("Paradigm: Using LuckPerms for permission checks.");
            } else {
                this.checker = new VanillaPermissionCheckerImpl();
                logger.info("Paradigm: LuckPerms not found. Using vanilla operator permissions for checks.");
            }
        }
    }

    private void registerPermissionsWithLuckPerms() {
        if (!FabricLoader.getInstance().isModLoaded("luckperms")) {
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
            Map<String, String> allPermissions = knownPermissionNodes();

            java.util.UUID dummyUuid = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");
            net.luckperms.api.model.user.User dummyUser = api.getUserManager().loadUser(dummyUuid).join();

            if (dummyUser != null) {
                for (String permission : allPermissions.keySet()) {
                    dummyUser.getCachedData().getPermissionData().checkPermission(permission);
                    debugLogger.debugLog("Registered permission with LuckPerms: " + permission);
                }
                logger.info("Paradigm: Made " + allPermissions.size() + " permissions visible to LuckPerms.");
            }
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("API isn't loaded")) {
                debugLogger.debugLog("LuckPerms not ready yet, retrying in " + (attemptCount + 1) + " seconds... (attempt " + (attemptCount + 1) + "/5)");
                scheduleRetry(attemptCount);
            } else {
                logger.warn("Paradigm: Failed to register permissions with LuckPerms: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Paradigm: Failed to register permissions with LuckPerms: " + e.getMessage());
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

    public boolean hasPermission(ServerPlayerEntity player, String permission) {
        if (player == null) return false;
        if (checker == null) {
            initialize();
        }
        return checker.hasPermission(player, permission);
    }

    public interface PermissionChecker {
        boolean hasPermission(ServerPlayerEntity player, String permission);
    }

    public static class LuckPermsCheckerImpl implements PermissionChecker {
        @Override
        public boolean hasPermission(ServerPlayerEntity player, String permission) {
            try {
                net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
                net.luckperms.api.model.user.User user = api.getUserManager().getUser(player.getUuid());
                if (user != null) {
                    net.luckperms.api.util.Tristate result = user.getCachedData().getPermissionData().checkPermission(permission);
                    return result.asBoolean();
                }
            } catch (Exception e) {
                return false;
            }
            return false;
        }
    }

    private static class VanillaPermissionCheckerImpl implements PermissionChecker {
        @Override
        public boolean hasPermission(ServerPlayerEntity player, String permission) {
            int requiredLevel = getPermissionLevelForVanilla(permission);
            return hasPermissionLevelSafe(player, requiredLevel);
        }

        private boolean hasPermissionLevelSafe(ServerPlayerEntity player, int level) {
            try {
                // Try direct call first (1.20.1, 1.21.1)
                return player.hasPermissionLevel(level);
            } catch (NoSuchMethodError e) {
                // Fallback for 1.21.8+ - try reflection
                try {
                    java.lang.reflect.Method method = player.getClass().getMethod("hasPermissionLevel", int.class);
                    return (boolean) method.invoke(player, level);
                } catch (Exception ex) {
                    // Final fallback - check if player is OP
                    try {
                        return player.getServer() != null &&
                               player.getServer().getPlayerManager() != null &&
                               player.getServer().getPlayerManager().isOperator(player.getGameProfile());
                    } catch (Exception finalEx) {
                        return false;
                    }
                }
            }
        }

        private int getPermissionLevelForVanilla(String permission) {
            if (permission == null) return 4;

            return switch (permission) {
                case STAFF_CHAT_PERMISSION, MENTION_EVERYONE_PERMISSION,
                        RESTART_MANAGE_PERMISSION, BROADCAST_PERMISSION, RELOAD_PERMISSION -> 2;
                case MENTION_PLAYER_PERMISSION, GROUPCHAT_PERMISSION -> 0;
                default -> permission.startsWith("paradigm.") ? 0 : 4;
            };
        }
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
