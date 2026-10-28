package eu.avalanche7.paradigm.modules.tab;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

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
        this(services.getPermissionsHandler());
    }

    TablistMetadataResolver(PermissionsHandler handler) {
        this(handler::isInternalPermissionsEnabled,
                player -> resolveInternal(handler, player),
                player -> resolveExternal(handler, player),
                handler::permissionsStateVersion, System::currentTimeMillis);
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
            if (!internalSource && cached != null && cached.internalSource == internalSource && now - cached.timestamp < CACHE_TTL_MILLIS
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
        return resolveMetadata(handler, player, TablistMetadata.Source.PARADIGM);
    }

    private static TablistMetadata resolveExternal(PermissionsHandler handler, IPlayer player) {
        return resolveMetadata(handler, player, TablistMetadata.Source.LUCKPERMS);
    }

    private static TablistMetadata resolveMetadata(PermissionsHandler handler, IPlayer player, TablistMetadata.Source source) {
        PermissionAPI.PermissionMeta meta = handler.resolvePlayerMetadata(player);
        return meta == null ? TablistMetadata.EMPTY : new TablistMetadata(meta.primaryGroup(), meta.prefix(), meta.suffix(), meta.weight(), source);
    }

    private record CacheEntry(TablistMetadata metadata, boolean internalSource, long stateVersion, long timestamp) {
    }
}
