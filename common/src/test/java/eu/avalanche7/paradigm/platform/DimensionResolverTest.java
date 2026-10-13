package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DimensionResolverTest {
    private record World(String id) {}
    private final List<World> worlds = List.of(new World("minecraft:overworld"), new World("minecraft:the_nether"),
            new World("minecraft:the_end"), new World("example:moon/base"), new World("other:overworld"));

    @ParameterizedTest
    @ValueSource(strings = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "example:moon/base", "other:overworld"})
    void registeredIdentifiersResolveToTheirOwnWorld(String identifier) {
        World expected = worlds.stream().filter(world -> world.id().equals(identifier)).findFirst().orElseThrow();
        assertSame(expected, DimensionResolver.resolveRegistered(identifier, worlds, World::id));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "0", "-1", "1", "7", "28", "DIM7", "DIM-1", "world", "spawn", "eventy", "hraci",
            "PERSONAL_DIM_213", "PlayerOne", "example:missing", "minecraft:missing", "overworld", "bad identifier",
            " minecraft:overworld", "minecraft:overworld "})
    void unknownIdentifiersNeverFallBackToAnotherWorld(String identifier) {
        assertNull(DimensionResolver.resolveRegistered(identifier, worlds, World::id));
    }

    @Test
    void existingForgeCaseInsensitiveMatchingIsPreserved() {
        assertSame(worlds.get(0), DimensionResolver.resolveRegistered("MINECRAFT:OVERWORLD", worlds, World::id));
    }

    @Test
    void missingWorldAndAmbiguousKeysFailClosed() {
        assertNull(DimensionResolver.resolveRegistered("minecraft:overworld", List.<World>of(), World::id));
        assertNull(DimensionResolver.resolveRegistered("minecraft:overworld",
                List.of(worlds.get(0), new World("minecraft:overworld")), World::id));
        assertSame(worlds.get(0), DimensionResolver.resolveRegistered("minecraft:overworld",
                List.of(worlds.get(0), worlds.get(0)), World::id));
    }

    @Test
    void resolutionUsesTheCurrentRegistryWithoutCaching() {
        World replacement = new World("example:moon/base");
        assertSame(replacement, DimensionResolver.resolveRegistered(replacement.id(), List.of(replacement), World::id));
        assertNull(DimensionResolver.resolveRegistered(replacement.id(), List.<World>of(), World::id));
    }
}
