package eu.avalanche7.paradigm.modules.permissions;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalPermissionNodeRegistryTest {
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
}
