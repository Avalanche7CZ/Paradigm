package eu.avalanche7.paradigm.utils;

import com.forgeessentials.api.APIRegistry;
import com.forgeessentials.api.permissions.DefaultPermissionLevel;
import dev.ftb.mods.ftbranks.api.FTBRanksAPI;
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
        registerPermissions();
    }

    private void initializeChecker() {
        if (checker == null) {
            if (ModList.get().isLoaded("luckperms")) {
                checker = new LuckPermsCheckerImpl();
                logger.info("Paradigm: Using LuckPermsChecker for permissions.");
            } else if (ModList.get().isLoaded("ftbranks")) {
                checker = new FTBRanksCheckerImpl();
                logger.info("Paradigm: Using FTBRanksChecker for permissions.");
            } else if (ModList.get().isLoaded("forgeessentials")) {
                try {
                    checker = new ForgeEssentialsCheckerImpl();
                    logger.info("Paradigm: Using ForgeEssentialsChecker for permissions.");
                } catch (NoClassDefFoundError e) {
                    logger.warn("Paradigm: ForgeEssentials mod classes not found, falling back to vanilla OP checks.");
                    checker = new ForgePermissionCheckerImpl();
                }
            } else {
                checker = new ForgePermissionCheckerImpl();
                logger.info("Paradigm: No compatible permissions mod found. Using vanilla OP level checks.");
            }
        }
    }

    private void registerPermissions() {
        if (checker instanceof ForgeEssentialsCheckerImpl) {
            ForgeEssentialsCheckerImpl feChecker = (ForgeEssentialsCheckerImpl) checker;
            ForgeEssentialsCheckerImpl.registerPermission(MENTION_EVERYONE_PERMISSION, "Allows mentioning everyone");
            ForgeEssentialsCheckerImpl.registerPermission(MENTION_PLAYER_PERMISSION, "Allows mentioning a player");
            ForgeEssentialsCheckerImpl.registerPermission(STAFF_CHAT_PERMISSION, "Allows access to staff chat");
        } else {
            this.debugLogger.debugLog("PermissionsHandler: Cannot register FE permissions. ForgeEssentials mod is not present or checker is not ForgeEssentialsCheckerImpl.");
        }

        if (this.cmConfig != null && checker instanceof ForgeEssentialsCheckerImpl) {
            this.cmConfig.getLoadedCommands().forEach(command -> {
                if (command.isRequirePermission() && command.getPermission() != null && !command.getPermission().isEmpty()) {
                    String permissionDescription = command.getDescription() != null ? command.getDescription() : "Execute the custom command: " + command.getName();
                    ForgeEssentialsCheckerImpl.registerPermission(command.getPermission(), permissionDescription);
                    this.debugLogger.debugLog("PermissionsHandler: Registered FE custom permission: " + command.getPermission() + " - " + permissionDescription);
                }
            });
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

    public static class FTBRanksCheckerImpl implements PermissionChecker {
        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            FTBRanksAPI api = FTBRanksAPI.INSTANCE;
            return api.getPermissionValue(player, permission).asBoolean().orElse(false);
        }
    }
    public static class ForgeEssentialsCheckerImpl implements PermissionChecker {
        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            return APIRegistry.perms.checkPermission(player, permission);
        }

        public static void registerPermission(String permission, String description) {
            DefaultPermissionLevel level = DefaultPermissionLevel.NONE;
            APIRegistry.perms.registerPermission(permission, level, description);
        }
    }

    public static class ForgePermissionCheckerImpl implements PermissionChecker {
        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            int permissionLevel = getPermissionLevelForVanilla(permission);
            return player.hasPermissions(permissionLevel);
        }

        private int getPermissionLevelForVanilla(String permission) {
            if (STAFF_CHAT_PERMISSION.equals(permission)) return 2;
            if (MENTION_EVERYONE_PERMISSION.equals(permission)) return MENTION_EVERYONE_PERMISSION_LEVEL;
            if (MENTION_PLAYER_PERMISSION.equals(permission)) return MENTION_PLAYER_PERMISSION_LEVEL;
            if ("paradigm.broadcast".equals(permission)) return BROADCAST_PERMISSION_LEVEL;
            return 0;
        }
    }
}
