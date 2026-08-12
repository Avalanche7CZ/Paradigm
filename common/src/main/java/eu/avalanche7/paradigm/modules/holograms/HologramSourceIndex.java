package eu.avalanche7.paradigm.modules.holograms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;

public final class HologramSourceIndex {

    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<ChunkKey, LinkedHashSet<String>> chunkIndex = new LinkedHashMap<>();
    private final Map<String, String> interactionSources = new HashMap<>();

    public void rebuild(HologramService.Config config, List<TemporaryHologram> temporary) {
        sources.clear();
        chunkIndex.clear();
        interactionSources.clear();
        if (config != null && config.enabled) {
            config.holograms.forEach((id, definition) ->
                    add(new Source(sourceId(false, id), id, true, definition.copy())));
        }
        if (temporary != null) {
            for (TemporaryHologram value : temporary) {
                add(new Source(sourceId(true, value.id), value.id, false, value.definition));
            }
        }
    }

    private void add(Source source) {
        sources.put(source.sourceId(), source);
        chunkIndex.computeIfAbsent(ChunkKey.of(source.location()), ignored -> new LinkedHashSet<>())
                .add(source.sourceId());
        if (source.definition().interaction.enabled) {
            interactionSources.put(HologramService.interactionOwnershipKey(source.id(), source.definition()),
                    source.sourceId());
        }
    }

    public Source source(String sourceId) {
        return sources.get(sourceId);
    }

    public boolean contains(String sourceId) {
        return sources.containsKey(sourceId);
    }

    public Set<String> sourceIds() {
        return sources.keySet();
    }

    public Collection<Source> all() {
        return sources.values();
    }

    public List<ChunkKey> chunks() {
        return new ArrayList<>(chunkIndex.keySet());
    }

    public Set<String> sourcesInChunk(ChunkKey key) {
        return chunkIndex.getOrDefault(key, new LinkedHashSet<>());
    }

    public String sourceForInteraction(String ownershipKey) {
        return interactionSources.get(ownershipKey);
    }

    public Set<String> validOwnershipKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Source source : sources.values()) {
            if (!source.definition().enabled) {
                continue;
            }
            for (int index = 0; index < source.definition().lines.size(); index++) {
                keys.add(HologramService.ownershipKey(source.id(), source.definition(), index));
            }
            if (source.definition().interaction.enabled) {
                keys.add(HologramService.interactionOwnershipKey(source.id(), source.definition()));
            }
        }
        return keys;
    }

    public static String sourceId(boolean temporary, String id) {
        return (temporary ? "temporary:" : "persistent:") + id;
    }

    public record Source(String sourceId, String id, boolean persistent, HologramDefinition definition) {
        public IHologramPlatform.Location location() {
            return new IHologramPlatform.Location(definition.dimension, definition.x, definition.y, definition.z);
        }
    }

    public record ChunkKey(String dimension, int x, int z) {
        public static ChunkKey of(IHologramPlatform.Location location) {
            return new ChunkKey(location.dimension(),
                    ((int) Math.floor(location.x())) >> 4, ((int) Math.floor(location.z())) >> 4);
        }

        public IHologramPlatform.Location location() {
            return new IHologramPlatform.Location(dimension, x * 16.0D, 0.0D, z * 16.0D);
        }
    }
}
