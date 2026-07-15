package eu.avalanche7.paradigm.modules.permissions;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalPermissionNodeRegistryTest {
    @Test
    void discoversExternalLiteralCommandsFromTheRegisteredDispatcher() {
        PermissionNodeRegistry registry = new PermissionNodeRegistry(
                LoggerFactory.getLogger("command-discovery-test"), null, null);
        FakeCommandNode root = new FakeCommandNode("root");
        FakeCommandNode pokeheal = new FakeCommandNode("pokeheal");
        pokeheal.add(new FakeCommandNode("other"));
        pokeheal.add(new FakeArgumentCommandNode("targets"));
        root.add(pokeheal);

        assertEquals(2, registry.discoverCommandTree(new FakeDispatcher(root)));
        assertEquals(List.of("command.pokeheal", "command.pokeheal.other"),
                registry.listNodes("pokeheal", 20).stream().map(node -> node.node).toList());
    }

    @Test
    void registrationIsOwnedIdempotentAndConflictSafe() {
        PermissionNodeRegistry registry = new PermissionNodeRegistry(
                LoggerFactory.getLogger("external-node-test"), null, null);
        PermissionNodeRegistry.ExternalRegistration first = registry.registerExternalNode(
                "paradigm_realms", "paradigm_realms.realm.use", "Use realms", 0, "Realms", "realm.use");
        PermissionNodeRegistry.ExternalRegistration duplicate = registry.registerExternalNode(
                "paradigm_realms", "paradigm_realms.realm.use", "Use realms", 0, "Realms", "realm.use");
        PermissionNodeRegistry.ExternalRegistration conflict = registry.registerExternalNode(
                "other_mod", "paradigm_realms.realm.use", "Replace realms", 4, "Admin", "replacement");

        assertEquals(PermissionNodeRegistry.ExternalRegistrationStatus.REGISTERED, first.status());
        assertEquals(PermissionNodeRegistry.ExternalRegistrationStatus.ALREADY_REGISTERED, duplicate.status());
        assertEquals(PermissionNodeRegistry.ExternalRegistrationStatus.CONFLICT, conflict.status());
        assertTrue(registry.knownNodes().containsKey("paradigm_realms.realm.use"));

        first.close();
        assertTrue(registry.knownNodes().containsKey("paradigm_realms.realm.use"));
        duplicate.close();
        assertFalse(registry.knownNodes().containsKey("paradigm_realms.realm.use"));
    }

    @Test
    void externalProtectNodesAppearInTheSharedCommandAndDashboardDiscoveryQuery() {
        PermissionNodeRegistry registry = new PermissionNodeRegistry(
                LoggerFactory.getLogger("protect-node-test"), null, null);
        PermissionNodeRegistry.ExternalRegistration first = registry.registerExternalNode(
                "paradigmprotect", "paradigmprotect.rollback", "Plan and confirm safe rollback operations.",
                3, "Rollback", "rollback");
        PermissionNodeRegistry.ExternalRegistration second = registry.registerExternalNode(
                "paradigmprotect", "paradigmprotect.rollback", "Plan and confirm safe rollback operations.",
                3, "Rollback", "rollback");

        assertEquals(PermissionNodeRegistry.ExternalRegistrationStatus.REGISTERED, first.status());
        assertEquals(PermissionNodeRegistry.ExternalRegistrationStatus.ALREADY_REGISTERED, second.status());
        assertTrue(registry.listNodes("protect", 20).stream()
                .anyMatch(node -> "paradigmprotect.rollback".equals(node.node)));
        first.close();
        assertTrue(registry.listNodes("protect", 20).stream()
                .anyMatch(node -> "paradigmprotect.rollback".equals(node.node)));

        second.close();
        assertFalse(registry.listNodes("protect", 20).stream()
                .anyMatch(node -> "paradigmprotect.rollback".equals(node.node)));
    }

    public static final class FakeDispatcher {
        private final FakeCommandNode root;

        private FakeDispatcher(FakeCommandNode root) {
            this.root = root;
        }

        public FakeCommandNode getRoot() {
            return root;
        }
    }

    public static class FakeCommandNode {
        private final String name;
        private final List<FakeCommandNode> children = new ArrayList<>();

        private FakeCommandNode(String name) {
            this.name = name;
        }

        private void add(FakeCommandNode child) {
            children.add(child);
        }

        public String getName() {
            return name;
        }

        public Collection<FakeCommandNode> getChildren() {
            return children;
        }
    }

    public static final class FakeArgumentCommandNode extends FakeCommandNode {
        private FakeArgumentCommandNode(String name) {
            super(name);
        }
    }
}
