package eu.avalanche7.paradigm.modules.chat;

import java.util.List;
import java.util.Locale;

public final class PlayerNameClickAction {

    public static final int MAX_VALUE_LENGTH = 256;
    public static final List<String> TYPES = List.of("none", "run_command", "suggest_command");

    public enum Type {
        NONE(""),
        RUN_COMMAND("RUN_COMMAND"),
        SUGGEST_COMMAND("SUGGEST_COMMAND");

        private final String platformAction;

        Type(String platformAction) {
            this.platformAction = platformAction;
        }

        public String platformAction() {
            return platformAction;
        }
    }

    public record Spec(Type type, String value) {
        public boolean enabled() {
            return type != Type.NONE && !value.isEmpty();
        }
    }

    public static final Spec DISABLED = new Spec(Type.NONE, "");

    private PlayerNameClickAction() {
    }

    public static String validateValueSyntax(String rawValue) {
        String value = rawValue == null ? "" : rawValue;
        if (value.isEmpty()) {
            return value;
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Player name click command must be a single line.");
        }
        if (value.indexOf('§') >= 0 || value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
            throw new IllegalArgumentException("Player name click command cannot contain formatting markup.");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Player name click command must be at most " + MAX_VALUE_LENGTH + " characters.");
        }
        return value;
    }

    public static Spec validate(String rawType, String rawValue) {
        String type = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty() || type.equals("none") || type.equals("disabled") || type.equals("off")) {
            return DISABLED;
        }
        Type resolved = switch (type) {
            case "run_command" -> Type.RUN_COMMAND;
            case "suggest_command" -> Type.SUGGEST_COMMAND;
            default -> throw new IllegalArgumentException(
                    "Player name click action must be one of: " + String.join(", ", TYPES) + ".");
        };

        String value = validateValueSyntax(rawValue);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Player name click action requires a command value.");
        }

        String normalized = value.stripLeading();
        while (normalized.startsWith("//")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.strip().length() <= 1) {
            throw new IllegalArgumentException("Player name click command requires a command name after the slash.");
        }
        return new Spec(resolved, normalized);
    }

    public static Spec validateOrDisabled(String rawType, String rawValue) {
        try {
            return validate(rawType, rawValue);
        } catch (IllegalArgumentException invalid) {
            return DISABLED;
        }
    }
}
