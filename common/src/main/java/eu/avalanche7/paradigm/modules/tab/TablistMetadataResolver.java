package eu.avalanche7.paradigm.modules.tab;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class TablistMetadataResolver implements TablistMetadataProvider {
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final long CACHE_TTL_MILLIS = 2000L;

    private final BooleanSupplier internalEnabled;
    private final TablistMetadataProvider internal;
    private final TablistMetadataProvider luckPerms;
    private final LongSupplier internalStateVersion;
    private final LongSupplier clock;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    public TablistMetadataResolver(Services services) {
        this(
                () -> services.getPermissionsHandler().isInternalPermissionsEnabled(),
                player -> resolveInternal(services.getPermissionsHandler(), player),
                TablistMetadataResolver::resolveLuckPerms,
                () -> services.getPermissionsHandler().permissionsStateVersion(),
                System::currentTimeMillis
        );
    }

    TablistMetadataResolver(BooleanSupplier internalEnabled, TablistMetadataProvider internal,
                            TablistMetadataProvider luckPerms) {
        this(internalEnabled, internal, luckPerms, () -> 0L, System::currentTimeMillis);
    }

    TablistMetadataResolver(BooleanSupplier internalEnabled, TablistMetadataProvider internal,
                            TablistMetadataProvider luckPerms, LongSupplier internalStateVersion, LongSupplier clock) {
        this.internalEnabled = internalEnabled;
        this.internal = internal;
        this.luckPerms = luckPerms;
        this.internalStateVersion = internalStateVersion;
        this.clock = clock;
    }

    @Override
    public synchronized TablistMetadata resolve(IPlayer player) {
        if (player == null) return TablistMetadata.EMPTY;
        boolean internalSource = internalEnabled.getAsBoolean();
        String uuid = player.getUUID();
        long now = clock.getAsLong();

        if (uuid != null) {
            CacheEntry cached = cache.get(uuid);
            if (cached != null && cached.internalSource == internalSource && now - cached.timestamp < CACHE_TTL_MILLIS
                    && (!internalSource || cached.stateVersion == internalStateVersion.getAsLong())) {
                return cached.metadata;
            }
        }

        TablistMetadata resolved = internalSource ? safeResolve(internal, player) : safeResolve(luckPerms, player);
        if (uuid != null) {
            cache.put(uuid, new CacheEntry(resolved, internalSource, internalStateVersion.getAsLong(), now));
        }
        return resolved;
    }

    public synchronized void invalidate(String uuid) {
        if (uuid != null) cache.remove(uuid);
    }

    public synchronized void invalidateAll() {
        cache.clear();
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

    private record CacheEntry(TablistMetadata metadata, boolean internalSource, long stateVersion, long timestamp) {
    }
}
