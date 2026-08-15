package eu.avalanche7.paradigm.modules.discord;

import java.util.Map;

public final class DiscordTemplates {
    public static final java.util.Set<String> FEATURE_TOKENS = java.util.Set.of(
            "message", "name", "online", "max",
            "icon", "action", "target", "actor", "reason", "duration", "duration_suffix",
            "punishment_id", "expiry", "scope",
            "time", "seconds");

    private DiscordTemplates() {
    }

    public static String apply(String template, Map<String, String> tokens) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (tokens == null || tokens.isEmpty()) {
            return template;
        }

        Map<String, String> normalized = new java.util.HashMap<>(tokens.size());
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            String key = key(entry.getKey());
            if (!key.isEmpty()) {
                normalized.put(key, entry.getValue() != null ? entry.getValue() : "");
            }
        }

        StringBuilder builder = new StringBuilder(template.length() + 32);
        int cursor = 0;
        while (cursor < template.length()) {
            char current = template.charAt(cursor);
            if (current != '{') {
                builder.append(current);
                cursor++;
                continue;
            }
            int close = template.indexOf('}', cursor + 1);
            if (close < 0) {
                builder.append(template, cursor, template.length());
                break;
            }
            String name = template.substring(cursor + 1, close);
            String value = normalized.get(name);
            if (value != null) {
                builder.append(value);
                cursor = close + 1;
            } else {
                builder.append(current);
                cursor++;
            }
        }
        return builder.toString();
    }

    private static String key(String raw) {
        String value = raw != null ? raw.trim() : "";
        if (value.startsWith("{") && value.endsWith("}") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        return value.matches("[A-Za-z0-9_]+") ? value : "";
    }

    public static String formatDuration(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainder = seconds % 60L;
        StringBuilder builder = new StringBuilder();
        if (hours > 0) {
            builder.append(hours).append('h');
        }
        if (minutes > 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(minutes).append('m');
        }
        if (remainder > 0 || builder.length() == 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(remainder).append('s');
        }
        return builder.toString();
    }
}
