package eu.avalanche7.paradigm.utils;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;

public class Lang {

    private final Logger logger;
    private volatile Map<String, String> translations = Map.of();
    private volatile String currentLanguage;
    private final MainConfigHandler.Config mainConfig;
    private final MessageParser messageParser;
    private final IPlatformAdapter platformAdapter;
    private final Path langFolder;

    public Lang(Logger logger, MainConfigHandler.Config mainConfig, MessageParser messageParser, IPlatformAdapter platformAdapter) {
        this.logger = logger;
        this.mainConfig = mainConfig;
        this.messageParser = messageParser;
        this.platformAdapter = platformAdapter;

        Path configDir;
        try {
            configDir = platformAdapter != null && platformAdapter.getConfig() != null
                    ? platformAdapter.getConfig().getConfigDirectory()
                    : java.nio.file.Path.of(System.getProperty("user.dir")).resolve("config");
        } catch (Throwable t) {
            configDir = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("config");
        }
        this.langFolder = configDir.resolve("paradigm").resolve("lang");

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
        var handler = MainConfigHandler.handler();
        var settings = handler != null ? handler.active() : mainConfig;
        if (settings == null || settings.defaultLanguage == null) {
            if (logger != null) logger.warn("Lang: MainConfig or defaultLanguage setting is null. Using default language 'en'.");
            loadLanguage("en");
            return;
        }
        String language = settings.defaultLanguage.get();
        if (logger != null) logger.info("Paradigm: Loaded language setting: {}", language);
        loadLanguage(language);
    }

    public void loadLanguage(String language) {
        if (language == null || !language.matches("[A-Za-z0-9_-]{1,32}")) language = "en";
        if (logger != null) logger.info("Paradigm: Attempting to load language: {}", language);
        Gson gson = new Gson();
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Path langFile = langFolder.resolve(language + ".json");

        Map<String, String> candidate = new HashMap<>();
        loadFromResource("en", gson, type, candidate);
        if (!"en".equalsIgnoreCase(language)) {
            loadFromResource(language, gson, type, candidate);
        }

        if (Files.exists(langFile)) {
            try (Reader reader = Files.newBufferedReader(langFile, StandardCharsets.UTF_8)) {
                Map<String, Object> rawMap = gson.fromJson(reader, type);
                flattenMap("", rawMap, candidate);
                if (logger != null) logger.info("Paradigm: Successfully loaded language from config: {}", language);
            } catch (Exception e) {
                if (logger != null) logger.error("Paradigm: Failed to load language file from config: " + language, e);
            }
        } else {
            if (logger != null) logger.warn("Language file not found in config: {}.json. Using defaults from resources.", language);
        }
        translations = Map.copyOf(candidate);
        currentLanguage = language;
    }

    private void loadFromResource(String langCode, Gson gson, Type type, Map<String, String> candidate) {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + langCode + ".json")) {
            if (in == null) {
                if (logger != null) logger.warn("Resource language file missing: /lang/{}.json", langCode);
                return;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Map<String, Object> rawMap = gson.fromJson(reader, type);
                flattenMap("", rawMap, candidate);
            }
        } catch (Exception e) {
            if (logger != null) logger.warn("Failed to load resource language file for: {}", langCode, e);
        }
    }

    private void flattenMap(String prefix, Map<String, Object> map, Map<String, String> candidate) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) {
                candidate.put(key, (String) value);
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> castedMap = (Map<String, Object>) value;
                flattenMap(key, castedMap, candidate);
            } else if (value != null) {
                candidate.put(key, value.toString());
            }
        }
    }

    public IComponent translate(String key) {
        String translatedText = translations.getOrDefault(key, key);
        translatedText = translatedText.replace("&", "§");
        if (this.messageParser == null) {
            if (logger != null) logger.warn("Lang.translate: MessageParser is null for key '{}'. Returning literal text.", key);
            return platformAdapter.createComponentFromLiteral(translatedText);
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

    public String getTranslation(String key) {
        return translations.getOrDefault(key, key);
    }

    public Map<String, String> getAllTranslations() {
        return new HashMap<>(translations);
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }
}
