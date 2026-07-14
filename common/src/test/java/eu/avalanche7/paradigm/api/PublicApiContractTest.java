package eu.avalanche7.paradigm.api;

import eu.avalanche7.paradigm.api.internal.ApiProviderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicApiContractTest {
    @AfterEach
    void resetFacade() {
        ApiProviderRegistry.uninstall();
    }

    @Test
    void unavailableFacadeHasDefinedNoOpBehavior() {
        ApiProviderRegistry.uninstall();
        assertFalse(ParadigmAPI.isAvailable());
        assertEquals(1, ParadigmAPI.apiVersion());
        assertEquals("unavailable", ParadigmAPI.modVersion());
        assertTrue(ParadigmAPI.capabilities().isEmpty());
        assertEquals(PermissionDecision.UNDEFINED, ParadigmAPI.permissions().check(
                java.util.UUID.randomUUID(), "example.node", PermissionContext.GLOBAL));
        assertFalse(ParadigmAPI.permissions().hasPermission(
                java.util.UUID.randomUUID(), "example.node", 4, PermissionContext.GLOBAL));
        assertEquals(MessageResult.API_UNAVAILABLE, ParadigmAPI.messages().sendPlayerMessage(
                java.util.UUID.randomUUID(), "hello", Map.of()));
        assertEquals(MessageResult.API_UNAVAILABLE, ParadigmAPI.messages().broadcastMessage("hello", Map.of()));
    }

    @Test
    void contextsAndMetadataAreImmutableAndValidated() {
        PermissionContext context = new PermissionContext(Map.of("server", "survival", "network", "main"));
        assertEquals(List.of("network", "server"), context.values().keySet().stream().toList());
        assertThrows(UnsupportedOperationException.class, () -> context.values().put("world", "overworld"));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.of("region", "spawn"));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.of("server", ""));

        PlayerPermissionMeta metadata = new PlayerPermissionMeta("admin", "[A]", "", List.of("admin", "default"));
        assertThrows(UnsupportedOperationException.class, () -> metadata.resolvedGroups().add("other"));
    }

    @Test
    void decisionConversionNeverReturnsNull() {
        assertEquals(PermissionDecision.ALLOW, PermissionDecision.fromNullable(Boolean.TRUE));
        assertEquals(PermissionDecision.DENY, PermissionDecision.fromNullable(Boolean.FALSE));
        assertEquals(PermissionDecision.UNDEFINED, PermissionDecision.fromNullable(null));
    }

    @Test
    void publicContractsContainNoMinecraftLoaderOrLuckPermsImports() throws IOException {
        Path sourceRoot = Path.of("src/main/java/eu/avalanche7/paradigm/api");
        try (var files = Files.list(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("import net.minecraft."), file.toString());
                assertFalse(source.contains("import net.fabricmc."), file.toString());
                assertFalse(source.contains("import net.minecraftforge."), file.toString());
                assertFalse(source.contains("import net.neoforged."), file.toString());
                assertFalse(source.contains("import net.luckperms."), file.toString());
                assertFalse(source.contains("import eu.avalanche7.paradigm.core.Services"), file.toString());
            }
        }
    }
}
