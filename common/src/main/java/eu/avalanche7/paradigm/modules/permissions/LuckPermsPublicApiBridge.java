package eu.avalanche7.paradigm.modules.permissions;

import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Isolates optional LuckPerms linkage from classes that must load when LuckPerms is absent. */
final class LuckPermsPublicApiBridge {
    private LuckPermsPublicApiBridge() {
    }

    static Boolean query(UUID playerUuid, String permission, PermissionContextSet context) {
        var user = LuckPermsProvider.get().getUserManager().getUser(playerUuid);
        if (user == null) return null;
        ImmutableContextSet.Builder contexts = ImmutableContextSet.builder();
        context.asMap().forEach(contexts::add);
        QueryOptions options = QueryOptions.contextual(contexts.build());
        Tristate state = user.getCachedData().getPermissionData(options).checkPermission(permission);
        return state == Tristate.UNDEFINED ? null : state.asBoolean();
    }

    static PermissionAPI.PermissionMeta metadata(UUID playerUuid) {
        LuckPerms api = LuckPermsProvider.get();
        var user = api.getUserManager().getUser(playerUuid);
        if (user == null) return null;
        var metadata = user.getCachedData().getMetaData();
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        groups.add(user.getPrimaryGroup());
        user.getInheritedGroups(user.getQueryOptions()).forEach(group -> groups.add(group.getName()));
        groups.removeIf(value -> value == null || value.isBlank());
        return new PermissionAPI.PermissionMeta(user.getPrimaryGroup(), metadata.getPrefix(), metadata.getSuffix(),
                List.copyOf(groups));
    }
}
