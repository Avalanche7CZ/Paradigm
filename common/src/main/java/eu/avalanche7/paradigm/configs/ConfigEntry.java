package eu.avalanche7.paradigm.configs;

public class ConfigEntry<T> {
    public String description;
    public T value;

    private ConfigEntry() {}

    public ConfigEntry(T value) {
        this.value = value;
        this.description = "";
    }

    public ConfigEntry(T value, String description) {
        this.value = value;
        this.description = description;
    }

    public T get() {
        return this.value;
    }

    public static <T> T valueOf(ConfigEntry<T> entry, T fallback) {
        return entry != null && entry.value != null ? entry.value : fallback;
    }
}
