package eu.avalanche7.paradigm.configs.schema;

import java.util.ArrayList;
import java.util.List;

public class ConfigValidationResult {
    private final List<String> accepted = new ArrayList<>();
    private final List<FieldError> rejected = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private String newRevision;

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
