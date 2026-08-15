package eu.avalanche7.paradigm.modules.discord.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class DiscordJson {
    private DiscordJson() {
    }

    public static JsonObject object(JsonObject parent, String member) {
        if (parent == null || !parent.has(member)) {
            return null;
        }
        JsonElement element = parent.get(member);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public static JsonArray array(JsonObject parent, String member) {
        if (parent == null || !parent.has(member)) {
            return null;
        }
        JsonElement element = parent.get(member);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    public static String string(JsonObject parent, String member) {
        if (parent == null || !parent.has(member)) {
            return null;
        }
        JsonElement element = parent.get(member);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    public static String string(JsonObject parent, String member, String fallback) {
        String value = string(parent, member);
        return value != null ? value : fallback;
    }

    public static Integer integer(JsonObject parent, String member) {
        if (parent == null || !parent.has(member)) {
            return null;
        }
        JsonElement element = parent.get(member);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException invalid) {
            return null;
        }
    }

    public static long longValue(JsonObject parent, String member, long fallback) {
        if (parent == null || !parent.has(member)) {
            return fallback;
        }
        JsonElement element = parent.get(member);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException | UnsupportedOperationException invalid) {
            return fallback;
        }
    }

    public static double doubleValue(JsonObject parent, String member, double fallback) {
        if (parent == null || !parent.has(member)) {
            return fallback;
        }
        JsonElement element = parent.get(member);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException invalid) {
            return fallback;
        }
    }

    public static boolean bool(JsonObject parent, String member, boolean fallback) {
        if (parent == null || !parent.has(member)) {
            return fallback;
        }
        JsonElement element = parent.get(member);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (UnsupportedOperationException invalid) {
            return fallback;
        }
    }
}
