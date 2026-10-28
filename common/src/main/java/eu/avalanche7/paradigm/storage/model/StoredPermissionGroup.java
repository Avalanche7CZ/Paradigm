package eu.avalanche7.paradigm.storage.model;

import java.util.List;

public record StoredPermissionGroup(
        String name,
        String description,
        String prefix,
        String suffix,
        int weight,
        List<String> parents,
        List<StoredPermissionNode> permissions,
        String chatNameFormat,
        String chatMessageFormat
) {
    public StoredPermissionGroup(String name, String description, String prefix, String suffix, int weight, List<String> parents, List<StoredPermissionNode> permissions) {
        this(name, description, prefix, suffix, weight, parents, permissions, "", "");
    }

    public StoredPermissionGroup {
        chatNameFormat = chatNameFormat != null ? chatNameFormat : "";
        chatMessageFormat = chatMessageFormat != null ? chatMessageFormat : "";
        parents = parents != null ? List.copyOf(parents) : List.of();
        permissions = permissions != null ? List.copyOf(permissions) : List.of();
    }
}
