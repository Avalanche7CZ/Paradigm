package eu.avalanche7.paradigm.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import net.minecraft.network.chat.Component;
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
    private final Path langFolder = FMLPaths.GAMEDIR.get().resolve("config/paradigm/lang");
    private final Map<String, String> translations = new HashMap<>();
    private String currentLanguage;
    private final MainConfigHandler.Config mainConfig;
    private final MessageParser messageParser;
    private final IPlatformAdapter platformAdapter;

    public Lang(Logger logger, MainConfigHandler.Config mainConfig, MessageParser messageParser, IPlatformAdapter platformAdapter) {
        this.logger = logger;
        this.mainConfig = mainConfig;
        this.messageParser = messageParser;
        this.platformAdapter = platformAdapter;
        try {
            ensureDefaultLangFiles();
        } catch (Exception e) {
            if (this.logger != null) {
                this.logger.error("Failed to initialize Lang class", e);
            } else {
                System.err.println("Failed to initialize Lang class and logger is null: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void initializeLanguage() {
        if (mainConfig == null || mainConfig.defaultLanguage == null) {
            if (logger != null) logger.error("Lang: MainConfig or defaultLanguage setting is null. Cannot initialize language.");
            return;
        }
        String language = mainConfig.defaultLanguage.get();
        if (logger != null) logger.info("Paradigm: Loaded language setting: {}", language);
        loadLanguage(language);
    }

    public void loadLanguage(String language) {
        if (logger != null) logger.info("Paradigm: Attempting to load language: {}", language);
        Gson gson = new Gson();
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Path langFile = langFolder.resolve(language + ".json");

        if (!Files.exists(langFile)) {
            if (logger != null) logger.error("Language file not found: {}. Attempting to use 'en'.", langFile);
            if (!language.equals("en")) {
                loadLanguage("en");
            } else {
                if (logger != null) logger.error("English language file also missing. Translations will not work.");
            }
            return;
        }

        try (Reader reader = Files.newBufferedReader(langFile, StandardCharsets.UTF_8)) {
            Map<String, Object> rawMap = gson.fromJson(reader, type);
            translations.clear();
            flattenMap("", rawMap);
            currentLanguage = language;
            if (logger != null) logger.info("Paradigm: Successfully loaded language: {}", language);
        } catch (Exception e) {
            if (logger != null) logger.error("Paradigm: Failed to load language file: " + language, e);
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
    public Component translate(String key) {
        String translatedText = translations.getOrDefault(key, key);
        translatedText = translatedText.replace("&", "§");
        if (this.messageParser == null) {
            if (logger != null) logger.warn("Lang.translate: MessageParser is null for key '{}'. Returning literal text.", key);
            return platformAdapter.createLiteralComponent(translatedText);
        }
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
                if (logger != null) logger.warn("Language file missing: {}.json. Attempting to copy from resources.", langCode);
                try (InputStream in = getClass().getResourceAsStream("/lang/" + langCode + ".json")) {
                    if (in == null) {
                        if (logger != null) logger.error("Default language file /lang/{}.json not found in JAR resources.", langCode);
                        continue;
                    }
                    Files.copy(in, langFile, StandardCopyOption.REPLACE_EXISTING);
                    if (logger != null) logger.info("Copied default language file for: {}", langCode);
                } catch (Exception e) {
                    if (logger != null) logger.warn("Failed to copy default language file for: " + langCode, e);
                }
            }
        }
    }
}
