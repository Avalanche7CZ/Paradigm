package eu.avalanche7.paradigm.utils.formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ComponentSlots {

    static final char MARKER_START = '\uE010';
    static final char MARKER_END = '\uE011';

    private static final ComponentSlots EMPTY = new ComponentSlots(List.of(), List.of());

    private final List<String> tokens;
    private final List<ComponentSlot> slots;

    private ComponentSlots(List<String> tokens, List<ComponentSlot> slots) {
        this.tokens = tokens;
        this.slots = slots;
    }

    public static ComponentSlots none() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    public String mark(String raw) {
        String text = strip(raw);
        if (text.isEmpty() || tokens.isEmpty()) {
            return text;
        }
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (text.contains(token)) {
                text = text.replace(token, MARKER_START + Integer.toString(index) + MARKER_END);
            }
        }
        return text;
    }

    public static String strip(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.indexOf(MARKER_START) < 0 && raw.indexOf(MARKER_END) < 0) {
            return raw;
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (current != MARKER_START && current != MARKER_END) {
                cleaned.append(current);
            }
        }
        return cleaned.toString();
    }

    ComponentSlot at(int index) {
        return index >= 0 && index < slots.size() ? slots.get(index) : null;
    }

    public static final class Builder {
        private final Map<String, ComponentSlot> entries = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder add(String token, ComponentSlot slot) {
            if (token != null && !token.isBlank() && slot != null) {
                entries.put(token, slot);
            }
            return this;
        }

        public ComponentSlots build() {
            if (entries.isEmpty()) {
                return EMPTY;
            }
            return new ComponentSlots(List.copyOf(entries.keySet()), new ArrayList<>(entries.values()));
        }
    }
}
