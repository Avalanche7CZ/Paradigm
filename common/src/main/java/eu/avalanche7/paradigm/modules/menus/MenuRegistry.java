package eu.avalanche7.paradigm.modules.menus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.Nullable;

public final class MenuRegistry {

    public enum Origin {
        CONFIG,
        PROGRAMMATIC
    }

    public record Entry(MenuDefinition definition, Origin origin) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();

    public long stateVersion() {
        return version.get();
    }

    public void register(MenuDefinition definition, Origin origin) {
        if (definition == null) {
            throw new IllegalArgumentException("A menu definition is required.");
        }
        definition.normalize();
        String key = key(definition.id);
        Entry existing = entries.get(key);
        if (existing != null && existing.origin() == Origin.PROGRAMMATIC && origin == Origin.CONFIG) {
            throw new IllegalArgumentException(
                    "Menu id '" + definition.id + "' is already registered by a Paradigm module.");
        }
        if (existing != null && origin == Origin.CONFIG) {
            throw new IllegalArgumentException("Duplicate menu id '" + definition.id + "'.");
        }
        if (existing != null && existing.origin() == Origin.PROGRAMMATIC && origin == Origin.PROGRAMMATIC) {
            throw new IllegalArgumentException(
                    "Menu id '" + definition.id + "' is already registered by another Paradigm module.");
        }
        entries.put(key, new Entry(definition, origin));
        version.incrementAndGet();
    }

    public boolean remove(String id) {
        boolean removed = entries.remove(key(id)) != null;
        if (removed) {
            version.incrementAndGet();
        }
        return removed;
    }

    public void replaceConfigDefinitions(Collection<MenuDefinition> definitions) {
        entries.entrySet().removeIf(entry -> entry.getValue().origin() == Origin.CONFIG);
        if (definitions != null) {
            for (MenuDefinition definition : definitions) {
                if (definition == null) {
                    continue;
                }
                String key = key(definition.id);
                if (entries.containsKey(key)) {
                    continue;
                }
                entries.put(key, new Entry(definition, Origin.CONFIG));
            }
        }
        version.incrementAndGet();
    }

    @Nullable
    public MenuDefinition get(String id) {
        Entry entry = entries.get(key(id));
        return entry != null ? entry.definition() : null;
    }

    @Nullable
    public Entry entry(String id) {
        return entries.get(key(id));
    }

    public boolean contains(String id) {
        return entries.containsKey(key(id));
    }

    public Set<String> ids() {
        return new TreeSet<>(entries.keySet());
    }

    public List<MenuDefinition> all() {
        List<MenuDefinition> out = new ArrayList<>();
        for (String id : ids()) {
            Entry entry = entries.get(id);
            if (entry != null) {
                out.add(entry.definition());
            }
        }
        return out;
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
        version.incrementAndGet();
    }

    private static String key(@Nullable String id) {
        return id != null ? id.trim().toLowerCase(Locale.ROOT) : "";
    }
}
