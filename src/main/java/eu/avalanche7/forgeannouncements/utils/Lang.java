package eu.avalanche7.forgeannouncements.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import eu.avalanche7.forgeannouncements.configs.MainConfigHandler;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Lang {

    private final Logger logger;
    private final Path langFolder = FMLPaths.GAMEDIR.get().resolve("config/forgeannouncements/lang");
    private final Map<String, String> translations = new HashMap<>();
    private String currentLanguage;
    private final MainConfigHandler.Config mainConfig;
    private final MessageParser messageParser;

    public Lang(Logger logger, MainConfigHandler.Config mainConfig, MessageParser messageParser) {
        this.logger = logger;
        this.mainConfig = mainConfig;
        this.messageParser = messageParser;
        try {
            ensureDefaultLangFiles();
        } catch (Exception e) {
            this.logger.error("Failed to initialize Lang class", e);
        }
    }

    public void initializeLanguage() {
        String language = mainConfig.defaultLanguage.get();
        logger.info("ForgeAnnouncements: Loaded language setting: {}", language);
        loadLanguage(language);
    }

    public void loadLanguage(String language) {
        logger.info("ForgeAnnouncements: Attempting to load language: {}", language);
        Gson gson = new Gson();
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Path langFile = langFolder.resolve(language + ".json");

        if (!Files.exists(langFile)) {
            logger.error("Language file not found: {}. Attempting to use 'en'.", langFile);
            if (!language.equals("en")) {
                loadLanguage("en");
            } else {
                logger.error("English language file also missing. Translations will not work.");
            }
            return;
        }

        try (Reader reader = Files.newBufferedReader(langFile, StandardCharsets.UTF_8)) {
            Map<String, Object> rawMap = gson.fromJson(reader, type);
            translations.clear();
            flattenMap("", rawMap);
            currentLanguage = language;
            logger.info("ForgeAnnouncements: Successfully loaded language: {}", language);
        } catch (Exception e) {
            logger.error("ForgeAnnouncements: Failed to load language file: " + language, e);
        }
    }

    private void flattenMap(String prefix, Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) {
                translations.put(key, (String) value);
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> castedMap = (Map<String, Object>) value;
                flattenMap(key, castedMap);
            } else if (value != null) {
                translations.put(key, value.toString());
            }
        }
    }

    public MutableComponent translate(String key) {
        String translatedText = translations.getOrDefault(key, key);
        translatedText = translatedText.replace("&", "§");
        return this.messageParser.parseMessage(translatedText, null);
    }

    private void ensureDefaultLangFiles() throws IOException {
        if (!Files.exists(langFolder)) {
            Files.createDirectories(langFolder);
        }

        List<String> availableLanguages = List.of("en", "cs", "ru");
        for (String langCode : availableLanguages) {
            Path langFile = langFolder.resolve(langCode + ".json");
            if (!Files.exists(langFile)) {
                logger.warn("Language file missing: {}.json. Attempting to copy from resources.", langCode);
                try (InputStream in = getClass().getResourceAsStream("/lang/" + langCode + ".json")) {
                    if (in == null) {
                        logger.error("Default language file /lang/{}.json not found in JAR resources.", langCode);
                        continue;
                    }
                    Files.copy(in, langFile, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Copied default language file for: {}", langCode);
                } catch (Exception e) {
                    logger.warn("Failed to copy default language file for: " + langCode, e);
                }
            }
        }
    }
}
