package eu.avalanche7.paradigm.storage.model;

import java.util.List;

public record StoredPermissionGroup(
        String name,
        String description,
        String prefix,
        String suffix,
        int weight,
        List<String> parents,
        List<StoredPermissionNode> permissions
) {
    public StoredPermissionGroup {
        parents = parents != null ? List.copyOf(parents) : List.of();
        permissions = permissions != null ? List.copyOf(permissions) : List.of();
    }
}
