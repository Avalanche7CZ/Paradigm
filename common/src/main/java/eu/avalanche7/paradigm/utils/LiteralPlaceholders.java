package eu.avalanche7.paradigm.utils;

import java.util.Comparator;
import java.util.Map;

/** Inserts caller data as parser literals rather than formatting instructions. */
public final class LiteralPlaceholders {
    private LiteralPlaceholders() {
    }

    public static String apply(String template, Map<String, String> placeholders) {
        String result = template != null ? template : "";
        if (placeholders == null || placeholders.isEmpty()) return result;
        for (Map.Entry<String, String> entry : placeholders.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> normalizeKey(entry.getKey()).length()).reversed())
                .toList()) {
            String key = normalizeKey(entry.getKey());
            if (key.isEmpty()) continue;
            result = result.replace("{" + key + "}", escape(entry.getValue() != null ? entry.getValue() : ""));
        }
        return result;
    }

    public static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace("\\", "\\\\").replace("<", "\\<").replace("&", "\\&");
    }

    private static String normalizeKey(String key) {
        String value = key != null ? key.trim() : "";
        if (value.startsWith("{") && value.endsWith("}") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        return value.matches("[A-Za-z0-9_.-]+") ? value : "";
    }
}
