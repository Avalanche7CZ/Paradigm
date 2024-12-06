package eu.avalanche7.forgeannouncements.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import eu.avalanche7.forgeannouncements.configs.MainConfigHandler;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class Lang {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path LANG_FOLDER = FMLPaths.GAMEDIR.get().resolve("world/serverconfig/forgeannouncements/lang");
    private static final Map<String, String> translations = new HashMap<>();
    private static String currentLanguage;

    static {
        try {
            ensureDefaultLangFile();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Lang class", e);
        }
    }

    public static void initializeLanguage() {
        String language = MainConfigHandler.CONFIG.defaultLanguage.get();
        LOGGER.info("Loaded language setting: " + language);
        loadLanguage(language);
    }

    public static void loadLanguage(String language) {
        LOGGER.info("Attempting to load language: " + language);
        Gson gson = new Gson();
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Path langFile = LANG_FOLDER.resolve(language + ".json");

        try (Reader reader = Files.newBufferedReader(langFile, StandardCharsets.UTF_8)) {
            Map<String, Object> rawMap = gson.fromJson(reader, type);
            translations.clear();
            flattenMap("", rawMap);
            currentLanguage = language;
            LOGGER.info("Successfully loaded language: " + language);
        } catch (Exception e) {
            LOGGER.error("Failed to load language file: " + language, e);
        }
    }

    private static void flattenMap(String prefix, Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                flattenMap(key, (Map<String, Object>) value);
            } else {
                translations.put(key, value.toString());
            }
        }
    }

    public static MutableComponent translate(String key) {
        String translatedText = translations.getOrDefault(key, key);
        return ColorUtils.parseMessageWithColor(translatedText);
    }

    public static void ensureDefaultLangFile() throws IOException {
        if (!Files.exists(LANG_FOLDER)) {
            Files.createDirectories(LANG_FOLDER);
        }

        List<String> availableLanguages = List.of("en", "cs", "ru");
        for (String lang : availableLanguages) {
            Path langFile = LANG_FOLDER.resolve(lang + ".json");
            if (!Files.exists(langFile)) {
                LOGGER.warn("Language file missing: " + lang + ".json");
                try (InputStream in = Lang.class.getResourceAsStream("/lang/" + lang + ".json");
                     Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                     Writer writer = Files.newBufferedWriter(langFile, StandardCharsets.UTF_8)) {
                    char[] buffer = new char[1024];
                    int length;
                    while ((length = reader.read(buffer)) != -1) {
                        writer.write(buffer, 0, length);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to copy default language file for: " + lang, e);
                }
            }
        }
    }
}
