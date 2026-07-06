package eu.avalanche7.paradigm.lang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LangResourceParityTest {
    @Test
    void localizedResourcesExposeTheSameKeys() {
        Set<String> en = keys("lang/en.json");
        Set<String> cs = keys("lang/cs.json");
        Set<String> ru = keys("lang/ru.json");

        assertEquals(en, cs, "cs.json must contain the same lang keys as en.json");
        assertEquals(en, ru, "ru.json must contain the same lang keys as en.json");
    }

    private static Set<String> keys(String resource) {
        InputStream stream = LangResourceParityTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(stream, "Missing resource: " + resource);
        JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> keys = new LinkedHashSet<>();
        collect(root, "", keys);
        return keys;
    }

    private static void collect(JsonObject object, String prefix, Set<String> keys) {
        for (var entry : object.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject()) {
                collect(value.getAsJsonObject(), key, keys);
            } else {
                keys.add(key);
            }
        }
    }
}
