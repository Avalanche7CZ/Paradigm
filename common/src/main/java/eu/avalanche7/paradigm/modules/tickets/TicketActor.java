package eu.avalanche7.paradigm.modules.tickets;

import java.util.function.Predicate;

import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public interface TicketActor {

    String uuid();

    String name();

    boolean has(PermissionDefinition permission);

    default boolean hasNode(String node, int fallbackLevel) {
        return node == null || node.isBlank();
    }

    default IPlayer player() {
        return null;
    }

    static TicketActor of(IPlayer player, PermissionsHandler permissions) {
        return new PlayerActor(player, permissions);
    }

    static TicketActor administrative(String uuid, String name, Predicate<PermissionDefinition> permissionCheck) {
        return new AdministrativeActor(uuid, name, permissionCheck);
    }

    final class PlayerActor implements TicketActor {
        private final IPlayer player;
        private final PermissionsHandler permissions;

        private PlayerActor(IPlayer player, PermissionsHandler permissions) {
            this.player = player;
            this.permissions = permissions;
        }

        @Override
        public String uuid() {
            return player != null ? player.getUUID() : null;
        }

        @Override
        public String name() {
            return player != null ? player.getName() : null;
        }

        @Override
        public boolean has(PermissionDefinition permission) {
            if (player == null || permission == null) {
                return false;
            }
            return permissions != null && permissions.hasPermission(player, permission);
        }

        @Override
        public boolean hasNode(String node, int fallbackLevel) {
            if (node == null || node.isBlank()) {
                return true;
            }
            if (player == null) {
                return false;
            }
            return permissions != null && permissions.hasPermission(player, node, fallbackLevel);
        }

        @Override
        public IPlayer player() {
            return player;
        }
    }

    final class AdministrativeActor implements TicketActor {
        private final String uuid;
        private final String name;
        private final Predicate<PermissionDefinition> permissionCheck;

        private AdministrativeActor(String uuid, String name, Predicate<PermissionDefinition> permissionCheck) {
            this.uuid = uuid;
            this.name = name;
            this.permissionCheck = permissionCheck;
        }

        @Override
        public String uuid() {
            return uuid;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean has(PermissionDefinition permission) {
            return permission != null && permissionCheck != null && permissionCheck.test(permission);
        }

        @Override
        public boolean hasNode(String node, int fallbackLevel) {
            if (node == null || node.isBlank()) {
                return true;
            }
            return permissionCheck != null
                    && permissionCheck.test(new PermissionDefinition(node, fallbackLevel, node));
        }
    }
}
