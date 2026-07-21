package eu.avalanche7.paradigm.modules.holograms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TemporaryHologramService {
    public static final int MAX_TEMPORARY_HOLOGRAMS = 250;
    private static final int MAX_LINES = 100;
    private static final int MAX_LINE_LENGTH = 4096;

    private final Map<String, TemporaryHologram> holograms = new LinkedHashMap<>();

    public synchronized TemporaryHologram create(HologramDefinition definition, String owner, Long ttlSeconds, Long expiresAt, long now) {
        if (holograms.size() >= MAX_TEMPORARY_HOLOGRAMS) throw new IllegalStateException("Temporary hologram limit reached.");
        if (definition == null) throw new IllegalArgumentException("Temporary hologram definition is required.");
        TemporaryHologram temporary = new TemporaryHologram();
        temporary.id = UUID.randomUUID().toString();
        temporary.definition = validatedDefinition(definition);
        temporary.createdAt = now;
        temporary.owner = owner != null ? owner.trim() : "";
        temporary.expiresAt = resolveExpiry(ttlSeconds, expiresAt, now);
        if (temporary.expiresAt != null && temporary.expiresAt <= now) throw new IllegalArgumentException("Temporary hologram expiry must be in the future.");
        holograms.put(temporary.id, temporary);
        return temporary.copy();
    }

    public synchronized TemporaryHologram update(String id, HologramDefinition definition, Long expiresAt) {
        TemporaryHologram current = require(id);
        if (definition != null) {
            current.definition = validatedDefinition(definition);
        }
        if (expiresAt != null) {
            if (expiresAt <= System.currentTimeMillis()) throw new IllegalArgumentException("Temporary hologram expiry must be in the future.");
            current.expiresAt = expiresAt;
        }
        return current.copy();
    }

    public synchronized boolean remove(String id) {
        return id != null && holograms.remove(id) != null;
    }

    public synchronized TemporaryHologram get(String id) {
        TemporaryHologram temporary = holograms.get(id);
        return temporary != null ? temporary.copy() : null;
    }

    public synchronized List<TemporaryHologram> list() {
        return holograms.values().stream().map(TemporaryHologram::copy)
                .sorted(Comparator.comparing(value -> value.createdAt)).toList();
    }

    public synchronized List<String> expire(long now) {
        List<String> expired = new ArrayList<>();
        holograms.entrySet().removeIf(entry -> {
            if (!entry.getValue().expired(now)) return false;
            expired.add(entry.getKey());
            return true;
        });
        return expired;
    }

    public synchronized void clear() {
        holograms.clear();
    }

    private TemporaryHologram require(String id) {
        TemporaryHologram temporary = holograms.get(id);
        if (temporary == null) throw new IllegalArgumentException("Unknown temporary hologram: " + id);
        return temporary;
    }

    private static HologramDefinition validatedDefinition(HologramDefinition definition) {
        HologramDefinition copy = definition.copy();
        copy.normalize(48.0D, 5);
        if (copy.lines.size() > MAX_LINES) throw new IllegalArgumentException("A temporary hologram may contain at most " + MAX_LINES + " lines.");
        for (String line : copy.lines) {
            if (line == null || line.length() > MAX_LINE_LENGTH) throw new IllegalArgumentException("A temporary hologram line may contain at most " + MAX_LINE_LENGTH + " characters.");
        }
        return copy;
    }

    private static Long resolveExpiry(Long ttlSeconds, Long expiresAt, long now) {
        if (expiresAt != null) return expiresAt;
        if (ttlSeconds == null || ttlSeconds <= 0L) return null;
        try {
            return Math.addExact(now, Math.multiplyExact(ttlSeconds, 1000L));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Temporary hologram TTL is too large.");
        }
    }
}
