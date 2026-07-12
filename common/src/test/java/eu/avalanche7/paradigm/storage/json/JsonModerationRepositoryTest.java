package eu.avalanche7.paradigm.storage.json;

import eu.avalanche7.paradigm.data.ModerationDataStore;
import eu.avalanche7.paradigm.modules.moderation.PunishmentIds;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentStatus;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    @Test
    void persistsStableLedgerIdAndRevocationAcrossReload() {
        StorageContext context = new StorageContext(new ServerIdentity("network", "server", "Server"));
        ModerationDataStore firstStore = new ModerationDataStore(null, null, testConfig());
        JsonModerationRepository first = new JsonModerationRepository(firstStore, context);
        long now = System.currentTimeMillis();
        PunishmentRecord created = new PunishmentRecord(PunishmentIds.create(), PunishmentType.BAN, ServerScope.GLOBAL,
                "network", null, "00000000-0000-0000-0000-000000000001", "Player", null, null,
                "Reason", null, "Console", now, now, null, null, null, null, null, now, Map.of());

        first.addPunishmentRecord(created);

        ModerationDataStore reloadedStore = new ModerationDataStore(null, null, testConfig());
        JsonModerationRepository reloaded = new JsonModerationRepository(reloadedStore, context);
        PunishmentRecord persisted = reloaded.findPunishmentRecord(created.punishmentId()).orElseThrow();
        assertEquals(created.punishmentId(), persisted.punishmentId());
        assertTrue(reloaded.revokePunishmentRecord(created.punishmentId(), now + 1L, null, "Console", "Appeal accepted"));

        ModerationDataStore finalStore = new ModerationDataStore(null, null, testConfig());
        PunishmentRecord revoked = new JsonModerationRepository(finalStore, context)
                .findPunishmentRecord(created.punishmentId()).orElseThrow();
        assertEquals(PunishmentStatus.REVOKED, revoked.status(now + 2L));
        assertEquals("Appeal accepted", revoked.revokeReason());
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
