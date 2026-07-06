package eu.avalanche7.paradigm.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StorageProviderTypeTest {
    @Test
    void parsesKnownProviderValues() {
        assertEquals(StorageProviderType.JSON, StorageProviderType.parse("json"));
        assertEquals(StorageProviderType.JSON, StorageProviderType.parse(""));
        assertEquals(StorageProviderType.JSON, StorageProviderType.parse(null));
        assertEquals(StorageProviderType.SQLITE, StorageProviderType.parse("sqlite"));
        assertEquals(StorageProviderType.MYSQL, StorageProviderType.parse("mysql"));
    }

    @Test
    void rejectsInvalidProviderValues() {
        assertNull(StorageProviderType.parse("postgres"));
    }
}
