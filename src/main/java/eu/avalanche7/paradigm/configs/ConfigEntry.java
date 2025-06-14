package eu.avalanche7.paradigm.configs;

public class ConfigEntry<T> {
    public String description;
    public T value;

    /**
     * No-argument constructor for Gson deserialization.
     */
    private ConfigEntry() {}

    public ConfigEntry(T value, String description) {
        this.value = value;
        this.description = description;
    }
}
