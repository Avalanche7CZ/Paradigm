package eu.avalanche7.forgeannouncements.utils;

import com.forgeessentials.api.APIRegistry;
import com.forgeessentials.api.permissions.DefaultPermissionLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.util.logging.Logger;


public class PermissionsHandler {
    private static final Logger LOGGER = Logger.getLogger(PermissionsHandler.class.getName());
    public static final String MENTION_EVERYONE_PERMISSION = "forgeannouncements.mention.everyone";
    public static final String MENTION_PLAYER_PERMISSION = "forgeannouncements.mention.player";
    public static final String STAFF_CHAT_PERMISSION = "forgeannouncements.staff";
    public static final int MENTION_EVERYONE_PERMISSION_LEVEL = 2;
    public static final int MENTION_PLAYER_PERMISSION_LEVEL = 2;
    public static final int BROADCAST_PERMISSION_LEVEL = 2;
    public static final int ACTIONBAR_PERMISSION_LEVEL = 2;
    public static final int TITLE_PERMISSION_LEVEL = 2;
    public static final int BOSSBAR_PERMISSION_LEVEL = 2;

    private static PermissionChecker checker;

    private static void initializeChecker() {
        if (checker == null) {
            if (ModList.get().isLoaded("luckperms")) {
                checker = new LuckPermsChecker();
                LOGGER.info("Using LuckPermsChecker");
            } else if (ModList.get().isLoaded("forgeessentials")) {
                try {
                    checker = new ForgeEssentialsChecker();
                    LOGGER.info("Using ForgeEssentialsChecker");
                } catch (NoClassDefFoundError e) {
                    LOGGER.info("ForgeEssentials mod not found, falling back to ForgePermissionChecker");
                    checker = new ForgePermissionChecker();
                }
            } else {
                checker = new ForgePermissionChecker();
                LOGGER.info("Using ForgePermissionChecker");
            }
        }
    }
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        initializeChecker();
        if (checker instanceof ForgeEssentialsChecker) {
            ((ForgeEssentialsChecker) checker).registerPermission(MENTION_EVERYONE_PERMISSION, "Allows mentioning everyone");
            ((ForgeEssentialsChecker) checker).registerPermission(MENTION_PLAYER_PERMISSION, "Allows mentioning a player");
            ((ForgeEssentialsChecker) checker).registerPermission(STAFF_CHAT_PERMISSION, "Allows access to staff chat");
        } else {
            LOGGER.warning("Cannot register permissions. ForgeEssentials mod is not present [NOT ERROR].");
        }
    }

    public static boolean hasPermission(ServerPlayer player, String permission) {
        return checker.hasPermission(player, permission);
    }

    public interface PermissionChecker {
        boolean hasPermission(ServerPlayer player, String permission);
    }


    public static class LuckPermsChecker implements PermissionChecker {
        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
            net.luckperms.api.model.user.UserManager userManager = api.getUserManager();
            net.luckperms.api.model.user.User user = userManager.getUser(player.getUUID());

            if (user != null) {
                boolean hasPermission = user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
                return hasPermission;
            } else {
                return false;
            }
        }
    }

    public static class ForgeEssentialsChecker implements PermissionChecker {
        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            return APIRegistry.perms.checkPermission(player, permission);
        }

        public static void registerPermission(String permission, String description) {
            DefaultPermissionLevel level = DefaultPermissionLevel.NONE;

            APIRegistry.perms.registerPermission(permission, level, description);

        }
    }

    public static class ForgePermissionChecker implements PermissionChecker {
        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            int permissionLevel = getPermissionLevel(permission);
            return player.hasPermissions(permissionLevel);
        }

        private int getPermissionLevel(String permission) {
            if (permission.equals(STAFF_CHAT_PERMISSION)) {
                return 2;
            }
            return 0;
        }
    }
}
