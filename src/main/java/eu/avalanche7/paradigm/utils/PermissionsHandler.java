package eu.avalanche7.paradigm.utils;

import com.forgeessentials.api.APIRegistry;
import com.forgeessentials.api.permissions.IPermissionsHelper;
import eu.avalanche7.paradigm.configs.CMConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import org.apache.logging.log4j.Logger;

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
        registerPermissions();
    }

    private void initializeChecker() {
        if (checker != null) return;

        if (Loader.isModLoaded("luckperms")) {
            checker = new LuckPermsChecker();
            logger.info("Paradigm: Using LuckPerms for permission checks.");
        } else if (Loader.isModLoaded("forgeessentials")) {
            checker = new ForgeEssentialsChecker();
            logger.info("Paradigm: Using ForgeEssentials for permission checks.");
        } else {
            checker = new ForgePermissionChecker();
            logger.info("Paradigm: No compatible permissions mod found. Using vanilla OP level checks.");
        }
    }

    private void registerPermissions() {
        if (checker instanceof ForgeEssentialsChecker) {
            this.debugLogger.debugLog("Registering permissions with ForgeEssentials.");
            ForgeEssentialsChecker feChecker = (ForgeEssentialsChecker) checker;
            feChecker.registerPermission(MENTION_EVERYONE_PERMISSION, DefaultPermissionLevel.OP, "Allows mentioning everyone");
            feChecker.registerPermission(MENTION_PLAYER_PERMISSION, DefaultPermissionLevel.ALL, "Allows mentioning a player");
            feChecker.registerPermission(STAFF_CHAT_PERMISSION, DefaultPermissionLevel.OP, "Allows access to staff chat");
            if (this.cmConfig != null) {
                this.cmConfig.getLoadedCommands().forEach(command -> {
                    if (command.isRequirePermission() && command.getPermission() != null && !command.getPermission().isEmpty()) {
                        String desc = command.getDescription() != null ? command.getDescription() : "Execute the custom command: " + command.getName();
                        feChecker.registerPermission(command.getPermission(), DefaultPermissionLevel.OP, desc);
                        this.debugLogger.debugLog("Registered FE custom permission: " + command.getPermission());
                    }
                });
            }
        }
    }

    public boolean hasPermission(EntityPlayerMP player, String permission) {
        if (player == null) return false;
        if (checker == null) {
            logger.warn("PermissionsHandler checker not initialized. Defaulting to OP check.");
            return player.canUseCommand(4, "");
        }
        return checker.hasPermission(player, permission);
    }

    // --- Inner Classes for Permission Checking ---

    public interface PermissionChecker {
        boolean hasPermission(EntityPlayerMP player, String permission);
    }

    public static class LuckPermsChecker implements PermissionChecker {
        @Override
        public boolean hasPermission(EntityPlayerMP player, String permission) {
            try {
                net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
                net.luckperms.api.model.user.User user = api.getUserManager().getUser(player.getUniqueID());
                if (user != null) {
                    return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
                }
            } catch (Exception e) {
                // Log error if LuckPerms integration fails
            }
            return false;
        }
    }

    public static class ForgeEssentialsChecker implements PermissionChecker {
        @Override
        public boolean hasPermission(EntityPlayerMP player, String permission) {
            IPermissionsHelper permissionsHelper = APIRegistry.perms;
            return permissionsHelper != null && permissionsHelper.checkPermission(player, permission);
        }

        public void registerPermission(String permission, DefaultPermissionLevel level, String description) {
            IPermissionsHelper permissionsHelper = APIRegistry.perms;
            if (permissionsHelper != null) {
                permissionsHelper.registerPermission(permission, level, description);
            }
        }
    }

    public static class ForgePermissionChecker implements PermissionChecker {
        @Override
        public boolean hasPermission(EntityPlayerMP player, String permission) {
            return player.canUseCommand(getPermissionLevel(permission), "");
        }

        private int getPermissionLevel(String permission) {
            switch (permission) {
                case MENTION_EVERYONE_PERMISSION: return MENTION_EVERYONE_PERMISSION_LEVEL;
                case MENTION_PLAYER_PERMISSION: return MENTION_PLAYER_PERMISSION_LEVEL;
                case STAFF_CHAT_PERMISSION: return 2; // Typically OP level
                default: return 4; // Default to admin only for unknown permissions
            }
        }
    }
}
