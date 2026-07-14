package eu.avalanche7.paradigm.api.internal;

import eu.avalanche7.paradigm.api.Registration;
import eu.avalanche7.paradigm.api.RegistrationStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalPlaceholderRegistryTest {
    @Test
    void enforcesNamespaceAndKeepsFirstResolverUntilLastHandleCloses() {
        ExternalPlaceholderRegistry registry = new ExternalPlaceholderRegistry(LoggerFactory.getLogger("placeholder-test"));
        assertEquals(RegistrationStatus.INVALID,
                registry.register("paradigm_realms", "realm_id", ignored -> "one").status());

        Registration first = registry.register("paradigm_realms", "paradigm_realms_realm_id", ignored -> "<admin>&x");
        Registration duplicate = registry.register("paradigm_realms", "paradigm_realms_realm_id", ignored -> "replacement");
        assertEquals(RegistrationStatus.REGISTERED, first.status());
        assertEquals(RegistrationStatus.ALREADY_REGISTERED, duplicate.status());
        assertEquals("ID=\\<admin>\\&x", registry.resolve("ID={paradigm_realms_realm_id}", null));

        first.close();
        assertTrue(duplicate.active());
        assertEquals("\\<admin>\\&x", registry.resolve("{paradigm_realms_realm_id}", null));
        duplicate.close();
        assertFalse(duplicate.active());
        assertEquals("{paradigm_realms_realm_id}", registry.resolve("{paradigm_realms_realm_id}", null));
    }

    @Test
    void collidingNormalizedOwnerCannotReplaceExistingResolver() {
        ExternalPlaceholderRegistry registry = new ExternalPlaceholderRegistry(null);
        registry.register("alpha-mod", "alpha_mod_value", ignored -> "first");
        Registration conflict = registry.register("alpha_mod", "alpha_mod_value", ignored -> "second");
        assertEquals(RegistrationStatus.CONFLICT, conflict.status());
        assertEquals("first", registry.resolve("{alpha_mod_value}", null));
    }
}
