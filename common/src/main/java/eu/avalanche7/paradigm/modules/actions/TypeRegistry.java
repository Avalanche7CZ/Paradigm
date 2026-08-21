package eu.avalanche7.paradigm.modules.actions;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

abstract class TypeRegistry<E> {

    private final Map<String, E> entries = new ConcurrentHashMap<>();
    private final Function<E, String> typeOf;

    protected TypeRegistry(Function<E, String> typeOf) {
        this.typeOf = typeOf;
    }

    protected void put(String type, E entry, String[] aliases, String requirement) {
        String key = normalize(type);
        if (key.isEmpty() || entry == null) {
            throw new IllegalArgumentException(requirement);
        }
        entries.put(key, entry);
        if (aliases == null) {
            return;
        }
        for (String alias : aliases) {
            String aliasKey = normalize(alias);
            if (!aliasKey.isEmpty()) {
                entries.put(aliasKey, entry);
            }
        }
    }

    @Nullable
    protected E entry(String type) {
        return entries.get(normalize(type));
    }

    public boolean unregister(String type) {
        String key = normalize(type);
        return !key.isEmpty() && entries.entrySet().removeIf(entry -> typeOf.apply(entry.getValue()).equals(key));
    }

    public boolean isRegistered(String type) {
        return entries.containsKey(normalize(type));
    }

    public Set<String> types() {
        Set<String> distinct = entries.values().stream()
                .map(typeOf)
                .collect(Collectors.toCollection(TreeSet::new));
        return Collections.unmodifiableSet(distinct);
    }

    public Set<String> knownNames() {
        return Collections.unmodifiableSet(new TreeSet<>(entries.keySet()));
    }

    public void clear() {
        entries.clear();
    }

    static String normalize(@Nullable String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
