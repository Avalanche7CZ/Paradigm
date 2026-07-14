package eu.avalanche7.paradigm.api.internal;

import eu.avalanche7.paradigm.api.ExternalPlaceholderResolver;
import eu.avalanche7.paradigm.api.PlaceholderContext;
import eu.avalanche7.paradigm.api.Registration;
import eu.avalanche7.paradigm.api.RegistrationStatus;
import eu.avalanche7.paradigm.utils.LiteralPlaceholders;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class ExternalPlaceholderRegistry {
    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Logger logger;

    ExternalPlaceholderRegistry(Logger logger) {
        this.logger = logger;
    }

    Registration register(String ownerModId, String placeholderKey, ExternalPlaceholderResolver resolver) {
        String owner = normalizeOwner(ownerModId);
        String key = normalizeKey(placeholderKey);
        if (owner == null || key == null || resolver == null || !key.startsWith(owner.replace('-', '_') + "_")) {
            return SimpleRegistration.inactive(ownerModId, placeholderKey, RegistrationStatus.INVALID);
        }

        synchronized (lock) {
            Entry existing = entries.get(key);
            if (existing != null) {
                if (!existing.owner.equals(owner)) {
                    if (logger != null) logger.warn("Paradigm: placeholder '{}' is already owned by '{}'.", key, existing.owner);
                    return SimpleRegistration.inactive(owner, key, RegistrationStatus.CONFLICT);
                }
                existing.references++;
                return handle(existing, RegistrationStatus.ALREADY_REGISTERED);
            }
            Entry created = new Entry(owner, key, resolver);
            entries.put(key, created);
            return handle(created, RegistrationStatus.REGISTERED);
        }
    }

    String resolve(String text, UUID playerUuid) {
        if (text == null || text.isEmpty()) return text != null ? text : "";
        java.util.List<Entry> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(entries.values());
        }
        String result = text;
        PlaceholderContext context = new PlaceholderContext(Optional.ofNullable(playerUuid));
        for (Entry entry : snapshot) {
            String token = "{" + entry.key + "}";
            if (!result.contains(token)) continue;
            try {
                String value = entry.resolver.resolve(context);
                result = result.replace(token, LiteralPlaceholders.escape(value != null ? value : ""));
            } catch (Throwable failure) {
                if (logger != null) logger.warn("Paradigm: external placeholder '{}' failed: {}", entry.key, failure.getMessage());
            }
        }
        return result;
    }

    void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }

    private Registration handle(Entry entry, RegistrationStatus status) {
        return new SimpleRegistration(entry.owner, entry.key, status, true, () -> {
            synchronized (lock) {
                Entry current = entries.get(entry.key);
                if (current != entry) return;
                current.references--;
                if (current.references <= 0) entries.remove(entry.key);
            }
        });
    }

    private static String normalizeOwner(String owner) {
        String value = owner != null ? owner.trim().toLowerCase(Locale.ROOT) : "";
        return value.matches("[a-z0-9_.-]+") ? value : null;
    }

    private static String normalizeKey(String key) {
        String value = key != null ? key.trim().toLowerCase(Locale.ROOT) : "";
        if (value.startsWith("{") && value.endsWith("}")) value = value.substring(1, value.length() - 1);
        return value.matches("[a-z0-9_]+") ? value : null;
    }

    private static final class Entry {
        private final String owner;
        private final String key;
        private final ExternalPlaceholderResolver resolver;
        private int references = 1;

        private Entry(String owner, String key, ExternalPlaceholderResolver resolver) {
            this.owner = owner;
            this.key = key;
            this.resolver = resolver;
        }
    }
}
