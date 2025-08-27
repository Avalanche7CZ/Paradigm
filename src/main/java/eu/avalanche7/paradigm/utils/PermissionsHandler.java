package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.CMConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

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

    public static final int MENTION_EVERYONE_PERMISSION_LEVEL = 2;
    public static final int MENTION_PLAYER_PERMISSION_LEVEL = 2;
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
                default -> 0;
            };
        }
    }
}