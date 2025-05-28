package eu.avalanche7.forgeannouncements.utils;


import eu.avalanche7.forgeannouncements.configs.CMConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

public class PermissionsHandler {
    private final Logger logger;
    private final CMConfig cmConfig;
    private final DebugLogger debugLogger;

    public static final String MENTION_EVERYONE_PERMISSION = "forgeannouncements.mention.everyone";
    public static final String MENTION_PLAYER_PERMISSION = "forgeannouncements.mention.player";
    public static final String STAFF_CHAT_PERMISSION = "forgeannouncements.staff";
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
                checker = new LuckPermsCheckerImpl();
                logger.info("ForgeAnnouncements: Using LuckPermsChecker for permissions.");

        }

        }
    }
    public boolean hasPermission(ServerPlayer player, String permission) {
        if (player == null) return false;
        if (checker == null) {
            logger.warn("PermissionsHandler: Checker not initialized when hasPermission called for player {} and permission {}. Attempting to initialize.", player.getName().getString(), permission);
            initialize();
            if (checker == null) {
                logger.error("PermissionsHandler: Checker is still null after re-initialization attempt. Defaulting to OP check (level 4).");
                return player.hasPermissions(4);
            }
        }
        return checker.hasPermission(player, permission);
    }

    public interface PermissionChecker {
        boolean hasPermission(ServerPlayer player, String permission);
    }

    public static class LuckPermsCheckerImpl implements PermissionChecker {
        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
            net.luckperms.api.model.user.UserManager userManager = api.getUserManager();
            net.luckperms.api.model.user.User user = userManager.getUser(player.getUUID());

            if (user != null) {
                return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
            } else {
                return false;
            }
        }
    }
        private int getPermissionLevelForVanilla(String permission) {
            if (STAFF_CHAT_PERMISSION.equals(permission)) return 2;
            if (MENTION_EVERYONE_PERMISSION.equals(permission)) return MENTION_EVERYONE_PERMISSION_LEVEL;
            if (MENTION_PLAYER_PERMISSION.equals(permission)) return MENTION_PLAYER_PERMISSION_LEVEL;
            if ("forgeannouncements.broadcast".equals(permission)) return BROADCAST_PERMISSION_LEVEL;
            return 0;
        }
    }
