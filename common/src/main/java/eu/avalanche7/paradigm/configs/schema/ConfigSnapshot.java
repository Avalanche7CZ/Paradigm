package eu.avalanche7.paradigm.configs.schema;

import java.util.List;

public record ConfigSnapshot(
        String revision,
        long createdAtMs,
        List<ConfigCategory> categories,
        List<ConfigField> fields
) {
}
