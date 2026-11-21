package eu.avalanche7.paradigm.modules.tab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

class TablistMetadataResolverTest {
    private final IPlayer player = new IPlayer() {
        @Override public String getName() { return "Alex"; }
        @Override public String getUUID() { return "00000000-0000-0000-0000-000000000001"; }
        @Override public Object getOriginalPlayer() { return null; }
    };

    @Test
    void liveHandlerKeepsTablistAndPlaceholdersConsistentAfterWeightAndDisplayChanges() {
        var handler = new eu.avalanche7.paradigm.modules.permissions.PermissionsHandler(
                org.slf4j.LoggerFactory.getLogger(getClass()), null,
                new eu.avalanche7.paradigm.utils.DebugLogger(null), null, null, null) {
            @Override public boolean isInternalPermissionsEnabled() { return true; }
        };
        try {
            handler.initialize();
            var id = java.util.UUID.fromString(player.getUUID());
            handler.createPermissionGroup("vip");
            handler.createPermissionGroup("staff");
            handler.setPermissionGroupMetadata("vip", "prefix", "[VIP]");
            handler.setPermissionGroupMetadata("vip", "weight", "10");
            handler.setPermissionGroupMetadata("staff", "prefix", "[Staff]");
            handler.setPermissionGroupMetadata("staff", "weight", "100");
            handler.assignPlayerGroup(id, "vip");
            handler.assignPlayerGroup(id, "staff");
            var resolver = new TablistMetadataResolver(handler);
            var placeholders = new eu.avalanche7.paradigm.utils.Placeholders();
            assertEquals("staff", resolver.resolve(player).group());
            assertEquals("[Staff]|staff", placeholders.replacePlaceholders("{prefix}|{group}", player));
            handler.setDisplayGroup(id, "vip");
            assertEquals("vip", resolver.resolve(player).group());
            assertEquals("[VIP]|vip", placeholders.replacePlaceholders("{prefix}|{group}", player));
            assertEquals(handler.resolvePlayerMetadata(player).weight(), resolver.resolve(player).groupWeight());
            handler.setDisplayGroup(id, "auto");
            handler.setPermissionGroupMetadata("vip", "weight", "200");
            assertEquals("vip", resolver.resolve(player).group());
            assertEquals("[VIP]|vip", placeholders.replacePlaceholders("{prefix}|{group}", player));

            var beforeInheritance = resolver.resolve(player);
            handler.createPermissionGroup("member");
            handler.addPermissionGroupParent("vip", "member");
            var afterInheritance = resolver.resolve(player);
            assertEquals(beforeInheritance.group(), afterInheritance.group());
            assertEquals(beforeInheritance.prefix(), afterInheritance.prefix());
            assertNotEquals(beforeInheritance, afterInheritance);
            assertEquals(List.of("default", "vip", "staff", "member"), afterInheritance.groups());
            assertEquals("default,vip,staff,member", placeholders.replacePlaceholders("{player_groups}", player));
        } finally {
            eu.avalanche7.paradigm.utils.Placeholders.setPermissionMetaResolver(null);
            eu.avalanche7.paradigm.utils.Placeholders.setPlayerAdapter(null);
        }
    }

    @Test
    void internalMetadataWinsWhenInternalPermissionsAreEnabled() {
        TablistMetadataResolver resolver = new TablistMetadataResolver(() -> true,
                ignored -> new TablistMetadata("admin", "[A]", "", 100, TablistMetadata.Source.PARADIGM),
                ignored -> new TablistMetadata("lp", "[LP]", "", 50, TablistMetadata.Source.LUCKPERMS));
        assertEquals(TablistMetadata.Source.PARADIGM, resolver.resolve(player).source());
        assertEquals(100, resolver.resolve(player).groupWeight());
    }

    @Test
    void disabledInternalPermissionsUseLuckPermsAndThenEmptyFallback() {
        TablistMetadataResolver luckPerms = new TablistMetadataResolver(() -> false,
                ignored -> { throw new AssertionError("internal provider must not be queried"); },
                ignored -> new TablistMetadata("vip", "[VIP]", "", 20, TablistMetadata.Source.LUCKPERMS));
        assertEquals(TablistMetadata.Source.LUCKPERMS, luckPerms.resolve(player).source());

        TablistMetadataResolver absent = new TablistMetadataResolver(() -> false, ignored -> null, ignored -> null);
        assertEquals(TablistMetadata.EMPTY, absent.resolve(player));
    }

    @Test
    void internalMetadataIsFreshWithinTheTtlWindow() {
        CountingProvider internal = new CountingProvider(TablistMetadata.Source.PARADIGM);
        long[] clock = {0L};
        TablistMetadataResolver resolver = new TablistMetadataResolver(() -> true, internal,
                ignored -> TablistMetadata.EMPTY, () -> 0L, () -> clock[0]);

        resolver.resolve(player);
        clock[0] = 1000L;
        resolver.resolve(player);
        assertEquals(2, internal.calls);
    }

    @Test
    void cacheExpiresAfterTheTtlWindow() {
        CountingProvider internal = new CountingProvider(TablistMetadata.Source.PARADIGM);
        long[] clock = {0L};
        TablistMetadataResolver resolver = new TablistMetadataResolver(() -> true, internal,
                ignored -> TablistMetadata.EMPTY, () -> 0L, () -> clock[0]);

        resolver.resolve(player);
        clock[0] = 5000L;
        resolver.resolve(player);
        assertEquals(2, internal.calls);
    }

    @Test
    void internalSourceCacheInvalidatesAssoonAsPermissionsStateVersionChanges() {
        CountingProvider internal = new CountingProvider(TablistMetadata.Source.PARADIGM);
        long[] version = {1L};
        TablistMetadataResolver resolver = new TablistMetadataResolver(() -> true, internal,
                ignored -> TablistMetadata.EMPTY, () -> version[0], System::currentTimeMillis);

        resolver.resolve(player);
        version[0] = 2L;
        resolver.resolve(player);
        assertEquals(2, internal.calls, "a permission/group mutation must invalidate the cached entry immediately");
    }

    @Test
    void explicitInvalidateDropsTheCachedEntryForThatPlayer() {
        CountingProvider internal = new CountingProvider(TablistMetadata.Source.PARADIGM);
        TablistMetadataResolver resolver = new TablistMetadataResolver(() -> true, internal,
                ignored -> TablistMetadata.EMPTY, () -> 0L, System::currentTimeMillis);

        resolver.resolve(player);
        resolver.invalidate(player.getUUID());
        resolver.resolve(player);
        assertEquals(2, internal.calls);
    }

    private static final class CountingProvider implements TablistMetadataProvider {
        private final TablistMetadata.Source source;
        private int calls;

        private CountingProvider(TablistMetadata.Source source) {
            this.source = source;
        }

        @Override
        public TablistMetadata resolve(IPlayer player) {
            calls++;
            return new TablistMetadata("group", "[P]", "", 10, source);
        }
    }
}
