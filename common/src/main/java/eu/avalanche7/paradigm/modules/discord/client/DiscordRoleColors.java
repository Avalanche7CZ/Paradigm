package eu.avalanche7.paradigm.modules.discord.client;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class DiscordRoleColors {
    private record Role(int color, int position) {
    }

    private final ConcurrentHashMap<String, Role> roles = new ConcurrentHashMap<>();

    void put(String roleId, int color, int position) {
        if (roleId == null || roleId.isBlank()) {
            return;
        }
        if (color == 0) {
            roles.remove(roleId);
        } else {
            roles.put(roleId, new Role(color, position));
        }
    }

    void remove(String roleId) {
        if (roleId != null) {
            roles.remove(roleId);
        }
    }

    void clear() {
        roles.clear();
    }

    Integer highestColor(List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return null;
        }
        Integer bestColor = null;
        int bestPosition = Integer.MIN_VALUE;
        for (String roleId : roleIds) {
            Role role = roleId != null ? roles.get(roleId) : null;
            if (role != null && role.position() > bestPosition) {
                bestPosition = role.position();
                bestColor = role.color();
            }
        }
        return bestColor;
    }
}
