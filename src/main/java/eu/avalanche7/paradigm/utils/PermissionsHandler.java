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
    public static final int MENTION_EVERYONE_PERMISSION_LEVEL = 2;
    public static final int MENTION_PLAYER_PERMISSION_LEVEL = 2;
    public static final int BROADCAST_PERMISSION_LEVEL = 2;
    public static final int ACTIONBAR_PERMISSION_LEVEL = 2;
    public static final int TITLE_PERMISSION_LEVEL = 2;
    public static final int BOSSBAR_PERMISSION_LEVEL = 2;

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
                case STAFF_CHAT_PERMISSION -> 2;
                case MENTION_EVERYONE_PERMISSION -> MENTION_EVERYONE_PERMISSION_LEVEL;
                case MENTION_PLAYER_PERMISSION -> MENTION_PLAYER_PERMISSION_LEVEL;
                case "paradigm.broadcast" -> BROADCAST_PERMISSION_LEVEL;
                default -> 0;
            };
        }
    }
}