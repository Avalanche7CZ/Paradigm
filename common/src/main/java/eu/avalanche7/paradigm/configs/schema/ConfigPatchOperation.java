package eu.avalanche7.paradigm.configs.schema;

public record ConfigPatchOperation(
        String key,
        Object value
) {
}
