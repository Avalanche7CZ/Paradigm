package eu.avalanche7.paradigm.utils;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import com.forgeessentials.api.APIRegistry;
import com.forgeessentials.api.permissions.IPermissionsHelper;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


@Mod.EventBusSubscriber(modid = "paradigm")
public class PermissionsHandler {

    private static final Logger LOGGER = LogManager.getLogger();
    public static final String MENTION_EVERYONE_PERMISSION = "paradigm.mention.everyone";
    public static final String MENTION_PLAYER_PERMISSION = "paradigm.mention.player";
    public static final int MENTION_EVERYONE_PERMISSION_LEVEL = 2;
    public static final int MENTION_PLAYER_PERMISSION_LEVEL = 2;
    public static final int BROADCAST_PERMISSION_LEVEL = 2;
    public static final int ACTIONBAR_PERMISSION_LEVEL = 2;
    public static final int TITLE_PERMISSION_LEVEL = 2;
    public static final int BOSSBAR_PERMISSION_LEVEL = 2;

    private static PermissionChecker checker;

    static {
        if (Loader.isModLoaded("luckperms")) {
            checker = new LuckPermsChecker();
        } else if (Loader.isModLoaded("forgeessentials")) {
            checker = new ForgeEssentialsChecker();
        } else {
            checker = new ForgePermissionChecker();
        }
    }

    public static boolean hasPermission(EntityPlayerMP player, String permission) {
        return checker.hasPermission(player, permission);
    }

    public interface PermissionChecker {
        boolean hasPermission(EntityPlayerMP player, String permission);
    }

    @Mod.EventHandler
    public static void onServerStarting(FMLServerStartingEvent event) {
        if (checker instanceof ForgeEssentialsChecker) {
            ((ForgeEssentialsChecker) checker).registerPermission(MENTION_EVERYONE_PERMISSION, DefaultPermissionLevel.NONE, "Allows mentioning everyone");
            ((ForgeEssentialsChecker) checker).registerPermission(MENTION_PLAYER_PERMISSION, DefaultPermissionLevel.NONE, "Allows mentioning a player");
        } else {
            LOGGER.info("Cannot register permissions. ForgeEssentials mod is not present [NOT ERROR].");
        }
    }

    public static class LuckPermsChecker implements PermissionChecker {
        @Override
        public boolean hasPermission(EntityPlayerMP player, String permission) {
            net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
            net.luckperms.api.model.user.User user = api.getUserManager().getUser(player.getUniqueID());

            if (user != null) {
                return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
            } else {
                return false;
            }
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
            } else {
                LOGGER.error("PermissionsHelper is null. Cannot register permission: " + permission);
            }
        }
    }

    public static class ForgePermissionChecker implements PermissionChecker {
        @Override
        public boolean hasPermission(EntityPlayerMP player, String permission) {
            int permissionLevel = getPermissionLevel(permission);
            return player.canUseCommand(permissionLevel, "");
        }

        private int getPermissionLevel(String permission) {
            switch (permission) {
                case MENTION_EVERYONE_PERMISSION:
                    return MENTION_EVERYONE_PERMISSION_LEVEL;
                case MENTION_PLAYER_PERMISSION:
                    return MENTION_PLAYER_PERMISSION_LEVEL;
                default:
                    return 0;
            }
        }
    }
}
