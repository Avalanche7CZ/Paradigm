package eu.avalanche7.paradigm.configs.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RemoteConfigValidationService {
    private final ConfigSchemaRegistry registry;

    public RemoteConfigValidationService(ConfigSchemaRegistry registry) {
        this.registry = registry;
    }

    public record ValidatedOperation(String key, Object value) {
    }

    public record ValidationOutcome(List<ValidatedOperation> accepted, List<ConfigValidationResult.FieldError> rejected) {
        public boolean ok() {
            return rejected.isEmpty();
        }
    }

    public ValidationOutcome validate(String section, List<ConfigPatchOperation> operations) {
        ConfigSnapshot snapshot = registry.snapshot();
        Map<String, ConfigField> byKey = new HashMap<>();
        for (ConfigField field : snapshot.fields()) {
            byKey.put(field.key(), field);
        }

        List<ValidatedOperation> accepted = new ArrayList<>();
        List<ConfigValidationResult.FieldError> rejected = new ArrayList<>();
        if (operations != null) {
            for (ConfigPatchOperation op : operations) {
                String key = op != null ? op.key() : null;
                ConfigField field = key != null ? byKey.get(key) : null;
                if (field == null) {
                    rejected.add(new ConfigValidationResult.FieldError(key != null ? key : "<unknown>", "Unknown config field."));
                    continue;
                }
                if (!field.category().equals(section)) {
                    rejected.add(new ConfigValidationResult.FieldError(key, "Field does not belong to section '" + section + "'."));
                    continue;
                }
                if (!RemoteConfigEligibility.isRemoteEligible(field)) {
                    rejected.add(new ConfigValidationResult.FieldError(key, "This setting is local-only and cannot be centrally managed."));
                    continue;
                }
                try {
                    Object validated = validateFieldValue(field, op.value());
                    accepted.add(new ValidatedOperation(key, validated));
                } catch (IllegalArgumentException invalid) {
                    rejected.add(new ConfigValidationResult.FieldError(key, invalid.getMessage()));
                }
            }
        }
        return new ValidationOutcome(accepted, rejected);
    }

    public static Object validateFieldValue(ConfigField field, Object rawValue) {
        return ConfigPatchService.validateValue(field, rawValue);
    }
}
