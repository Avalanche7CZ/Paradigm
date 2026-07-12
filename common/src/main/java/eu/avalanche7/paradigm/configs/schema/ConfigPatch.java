package eu.avalanche7.paradigm.configs.schema;

import java.util.List;

public record ConfigPatch(
        String revision,
        List<ConfigPatchOperation> operations
) {
}
