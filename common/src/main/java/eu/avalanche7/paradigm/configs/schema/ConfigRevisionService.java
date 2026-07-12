package eu.avalanche7.paradigm.configs.schema;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ConfigRevisionService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ConfigRevisionService() {
    }

    public static String revision(ConfigSnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String json = GSON.toJson(snapshot.fields());
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12 && i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Long.toHexString(System.currentTimeMillis());
        }
    }
}
