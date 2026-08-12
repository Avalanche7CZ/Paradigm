package eu.avalanche7.paradigm.core;

import java.util.Objects;

import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.CommandToggleStore;

public final class CommandAccess {

    private final PermissionsHandler permissions;
    private final CommandToggleStore toggles;

    public CommandAccess(PermissionsHandler permissions, CommandToggleStore toggles) {
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.toggles = Objects.requireNonNull(toggles, "toggles");
    }

    public boolean isCommandEnabled(String root) {
        return root != null && toggles.isEnabled(root);
    }

    public boolean allows(IPlayer player, PermissionDefinition permission) {
        return player != null && permission != null
                && permissions.hasPermission(player, permission.node(), permission.fallbackLevel());
    }

    public boolean allowsPlayer(ICommandSource source, String root, PermissionDefinition permission) {
        return isCommandEnabled(root) && source != null && allows(source.getPlayer(), permission);
    }

    public boolean allowsSource(ICommandSource source, String root, PermissionDefinition permission) {
        if (!isCommandEnabled(root) || source == null) {
            return false;
        }
        return source.isConsole() || allows(source.getPlayer(), permission);
    }

    public boolean allowsSource(ICommandSource source, String root, String permission, int fallbackLevel) {
        if (!isCommandEnabled(root) || source == null) {
            return false;
        }
        if (source.isConsole()) {
            return true;
        }
        IPlayer player = source.getPlayer();
        return player != null && permissions.hasPermission(player, permission, fallbackLevel);
    }
}
