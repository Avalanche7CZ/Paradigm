package eu.avalanche7.paradigm.configs.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConfigValidationResult {
    private final List<String> accepted = new ArrayList<>();
    private final List<FieldError> rejected = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private String newRevision;
    private final Map<String, ConfigReloadBehavior> pending = new LinkedHashMap<>();
    private final List<String> applied = new ArrayList<>();

    public void pending(String key, ConfigReloadBehavior behavior) {
        pending.put(key, behavior);
    }

    public Map<String, ConfigReloadBehavior> pending() {
        return Map.copyOf(pending);
    }

    public void applied(String section) {
        applied.add(section);
    }

    public List<String> applied() {
        return List.copyOf(applied);
    }

    public boolean locallyApplied() {
        return ok() && pending.isEmpty();
    }

    public List<String> accepted() {
        return List.copyOf(accepted);
    }

    public List<FieldError> rejected() {
        return List.copyOf(rejected);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public String newRevision() {
        return newRevision;
    }

    public boolean ok() {
        return rejected.isEmpty();
    }

    public void accept(String key) {
        accepted.add(key);
    }

    public void reject(String key, String reason) {
        rejected.add(new FieldError(key, reason));
    }

    public void warn(String warning) {
        warnings.add(warning);
    }

    public void newRevision(String newRevision) {
        this.newRevision = newRevision;
    }

    public record FieldError(String key, String reason) {
    }
}
