package eu.avalanche7.paradigm.modules.tab;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;

import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class TablistMetadataResolver implements TablistMetadataProvider {
    private final BooleanSupplier internalEnabled;
    private final TablistMetadataProvider internal;
    private final TablistMetadataProvider luckPerms;

    public TablistMetadataResolver(Services services) {
        this(
                () -> services.getPermissionsHandler().isInternalPermissionsEnabled(),
                player -> resolveInternal(services.getPermissionsHandler(), player),
                TablistMetadataResolver::resolveLuckPerms
        );
    }

    TablistMetadataResolver(BooleanSupplier internalEnabled, TablistMetadataProvider internal,
                            TablistMetadataProvider luckPerms) {
        this.internalEnabled = internalEnabled;
        this.internal = internal;
        this.luckPerms = luckPerms;
    }

    @Override
    public TablistMetadata resolve(IPlayer player) {
        if (player == null) return TablistMetadata.EMPTY;
        if (internalEnabled.getAsBoolean()) return safeResolve(internal, player);
        return safeResolve(luckPerms, player);
    }

    private static TablistMetadata safeResolve(TablistMetadataProvider provider, IPlayer player) {
        try {
            TablistMetadata value = provider != null ? provider.resolve(player) : null;
            return value != null ? value : TablistMetadata.EMPTY;
        } catch (LinkageError | RuntimeException ignored) {
            return TablistMetadata.EMPTY;
        }
    }

    private static TablistMetadata resolveInternal(PermissionsHandler handler, IPlayer player) {
        PermissionAPI.PermissionMeta meta = handler.resolvePlayerMetadata(player);
        if (meta == null) return TablistMetadata.EMPTY;
        PermissionAPI.GroupInfo group = handler.getPermissionGroupInfo(meta.primaryGroup());
        return new TablistMetadata(meta.primaryGroup(), meta.prefix(), meta.suffix(),
                group != null ? group.weight() : 0, TablistMetadata.Source.PARADIGM);
    }

    private static TablistMetadata resolveLuckPerms(IPlayer player) {
        if (player.getUUID() == null) return TablistMetadata.EMPTY;
        LuckPerms api = LuckPermsProvider.get();
        User user = api.getUserManager().getUser(UUID.fromString(player.getUUID()));
        if (user == null) return TablistMetadata.EMPTY;
        String primary = user.getPrimaryGroup();
        Group group = api.getGroupManager().getGroup(primary);
        int weight = group != null ? group.getWeight().orElse(0) : 0;
        var metadata = user.getCachedData().getMetaData();
        return new TablistMetadata(primary, metadata.getPrefix(), metadata.getSuffix(), weight,
                TablistMetadata.Source.LUCKPERMS);
    }
}
