package eu.avalanche7.paradigm.configs.schema;

public record ConfigFieldValue(
        Object value,
        boolean secret,
        boolean set
) {
    public static ConfigFieldValue plain(Object value) {
        return new ConfigFieldValue(value, false, value != null);
    }

    public static ConfigFieldValue masked(boolean set) {
        return new ConfigFieldValue(null, true, set);
    }
}
