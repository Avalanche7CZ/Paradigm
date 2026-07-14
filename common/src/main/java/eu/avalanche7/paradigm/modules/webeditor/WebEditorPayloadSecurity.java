package eu.avalanche7.paradigm.modules.webeditor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Set;

/** Security boundary for configuration data sent to the external WebEditor. */
final class WebEditorPayloadSecurity {
    private static final Set<String> PRIVATE_FILE_KEYS = Set.of(
            "storage",
            "dashboard",
            "dashboardheartbeats",
            "permissions",
            "discoveredpermissions",
            "adminutils",
            "warps"
    );

    private WebEditorPayloadSecurity() {
    }

    static void requireSafe(JsonObject payload) {
        if (payload == null) {
            throw new IllegalArgumentException("WebEditor payload must not be null");
        }
        inspect(payload, "payload");
    }

    private static void inspect(JsonElement element, String path) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                inspect(array.get(index), path + "[" + index + "]");
            }
            return;
        }

        JsonObject object = element.getAsJsonObject();
        boolean filesObject = "payload.files".equals(path);
        for (var entry : object.entrySet()) {
            String normalized = normalize(entry.getKey());
            String fileKey = normalized.endsWith("json") ? normalized.substring(0, normalized.length() - 4) : normalized;
            if (isSecretKey(normalized) || (filesObject && PRIVATE_FILE_KEYS.contains(fileKey))) {
                throw new IllegalArgumentException("WebEditor payload contains private data at " + path + "." + entry.getKey());
            }
            inspect(entry.getValue(), path + "." + entry.getKey());
        }
    }

    private static boolean isSecretKey(String key) {
        return key.equals("password")
                || key.equals("passwd")
                || key.equals("pwd")
                || key.equals("credential")
                || key.equals("credentials")
                || key.equals("token")
                || key.startsWith("password")
                || key.endsWith("password")
                || key.endsWith("passwd")
                || key.endsWith("secret")
                || key.endsWith("apikey")
                || key.endsWith("accesstoken")
                || key.endsWith("refreshtoken")
                || key.endsWith("authtoken")
                || key.endsWith("bearertoken")
                || key.endsWith("privatekey");
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
