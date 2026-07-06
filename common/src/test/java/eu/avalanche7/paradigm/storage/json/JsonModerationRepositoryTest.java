package eu.avalanche7.paradigm.storage.json;

import eu.avalanche7.paradigm.data.ModerationDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonModerationRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsPermanentBanMetadata() {
        ModerationDataStore store = new ModerationDataStore(null, null, testConfig());
        JsonModerationRepository repository = new JsonModerationRepository(
                store,
                new StorageContext(new ServerIdentity("network", "server", "Server"))
        );

        repository.addPunishment(new StoredPunishment(
                0L,
                "ban",
                ServerScope.GLOBAL,
                null,
                null,
                "Player",
                "Reason",
                "Console",
                123L,
                null,
                true
        ));

        List<StoredPunishment> punishments = repository.listPunishments();
        assertEquals(1, punishments.size());
        assertEquals("ban", punishments.get(0).type());
        assertEquals("Player", punishments.get(0).name());

        assertTrue(repository.deactivateActivePunishments("ban", null, "player"));
        assertTrue(repository.listPunishments().isEmpty());
    }

    private IConfig testConfig() {
        return new IConfig() {
            @Override
            public Path getConfigDirectory() {
                return tempDir;
            }

            @Override
            public String getModId() {
                return "paradigm";
            }
        };
    }
}
