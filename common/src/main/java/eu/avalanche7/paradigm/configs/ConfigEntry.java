package eu.avalanche7.paradigm.configs;

public class ConfigEntry<T> {
    public String description;
    public T value;

    /**
     * No-argument constructor for Gson deserialization.
     */
    private ConfigEntry() {}

    public ConfigEntry(T value) {
        this.value = value;
        this.description = "";
    }

    public ConfigEntry(T value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * Gets the configured value.
     * @return The value of the configuration entry.
     */
    public T get() {
        return this.value;
    }
}
