package eu.avalanche7.paradigm.configs;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.AtomicFileIO;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.JsonValidator;

public abstract class BaseConfigHandler<T> {

    protected final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    protected final Logger logger;
    protected final IConfig platformConfig;
    protected final String configFileName;
    protected JsonValidator jsonValidator;
    protected volatile T config;
    private volatile T savedSettings;

    protected BaseConfigHandler(Logger logger, IConfig platformConfig, String configFileName) {
        this.logger = logger;
        this.platformConfig = platformConfig;
        this.configFileName = configFileName;
    }

    public void setJsonValidator(DebugLogger debugLogger) {
        this.jsonValidator = new JsonValidator(debugLogger);
    }

    protected Path getConfigPath() {
        return platformConfig.resolveConfigPath("paradigm/" + configFileName);
    }

    protected abstract T createDefaultConfig();

    protected abstract Class<T> getConfigClass();

    @SuppressWarnings("unchecked")
    protected void mergeConfigs(T defaults, T loaded) {
        try {
            Field[] fields = getConfigClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.getType() == ConfigEntry.class) {
                    field.setAccessible(true);
                    ConfigEntry<?> loadedEntry = (ConfigEntry<?>) field.get(loaded);
                    ConfigEntry<Object> defaultEntry = (ConfigEntry<Object>) field.get(defaults);

                    if (loadedEntry != null && loadedEntry.value != null) {
                        defaultEntry.value = loadedEntry.value;
                        logger.trace("[Paradigm] Preserved user setting for: " + field.getName());
                    } else {
                        logger.trace("[Paradigm] Using default value for new/missing config: " + field.getName());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[Paradigm] Error merging configs", e);
        }
    }

    public T active() {
        return config;
    }

    public void publish(T candidate) {
        config = candidate;
    }

    public T copy(T source) {
        return GSON.fromJson(GSON.toJsonTree(source), getConfigClass());
    }

    public JsonElement settings(T source) {
        return GSON.toJsonTree(source);
    }

    public String fileName() {
        return configFileName;
    }

    public T reviewed() {
        T saved = savedSettings;
        return saved == null ? active() : saved;
    }

    private void recordSaved(T value) {
        savedSettings = copy(value);
    }

    public T editableCopy() {
        return copy(reviewed());
    }

    public T forRuntime(T candidate) {
        return candidate;
    }

    public record Candidate<T>(BaseConfigHandler<T> handler, T value, JsonElement previousSettings) {
        public void save() {
            handler.save(value);
            recordSaved();
        }

        public void recordSaved() {
            handler.recordSaved(value);
        }

        public boolean differsFromDisk() {
            return !previousSettings.equals(handler.settings(value));
        }

        public boolean differsFromRuntime() {
            return !handler.settings(handler.active()).equals(handler.settings(handler.forRuntime(value)));
        }

        public Runnable publication() {
            T active = handler.forRuntime(value);
            return () -> handler.publish(active);
        }
    }

    public Candidate<T> edit() {
        return edit(ignored -> {});
    }

    public Candidate<T> edit(Consumer<? super T> validate) {
        T candidate = Files.notExists(getConfigPath()) ? editableCopy() : read();
        validate.accept(candidate);
        return new Candidate<>(this, candidate, settings(candidate));
    }

    public Candidate<T> editForPatch(String reloadName, Consumer<? super T> validate) {
        T candidate = read();
        validate.accept(candidate);
        if (!samePatchSettings(candidate, reviewed())) {
            throw new IllegalArgumentException(configFileName + " changed on disk. Reload " + reloadName
                    + ", then refresh the editor and retry.");
        }
        return new Candidate<>(this, candidate, settings(candidate));
    }

    protected boolean samePatchSettings(T disk, T saved) {
        return settings(disk).equals(settings(saved));
    }

    public Candidate<T> loadCandidate() {
        T candidate = read();
        return new Candidate<>(this, candidate, settings(candidate));
    }

    public T load() {
        T candidate;
        try {
            candidate = read();
        } catch (RuntimeException failure) {
            logger.warn("[Paradigm] Could not load {}, using defaults", configFileName, failure);
            return createDefaultConfig();
        }
        try {
            save(candidate);
        } catch (RuntimeException failure) {
            logger.warn("[Paradigm] Loaded {} but could not update the file", configFileName, failure);
        }
        return candidate;
    }

    public T read() {
        Path path = getConfigPath();
        T defaults = createDefaultConfig();
        if (Files.notExists(path)) return defaults;
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (jsonValidator != null) {
                JsonValidator.ValidationResult validation = jsonValidator.validateAndFix(content);
                if (!validation.isValid()) throw new IllegalArgumentException(validation.getMessage());
                content = validation.getFixedJson();
            }
            JsonElement json = JsonParser.parseString(content);
            if (!json.isJsonObject()) throw new IllegalArgumentException("Expected a JSON object.");
            checkTypes("", GSON.toJsonTree(defaults), json);
            T loaded = GSON.fromJson(json, getConfigClass());
            mergeConfigs(defaults, loaded);
            return defaults;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("Could not read " + configFileName + ": " + failure.getMessage(), failure);
        }
    }

    private static void checkTypes(String key, JsonElement expected, JsonElement supplied) {
        if (supplied.isJsonNull() || expected.isJsonNull()) return;
        if (expected.isJsonObject() && supplied.isJsonObject()) {
            for (var entry : expected.getAsJsonObject().entrySet()) {
                JsonElement value = supplied.getAsJsonObject().get(entry.getKey());
                if (value != null) checkTypes(key + "." + entry.getKey(), entry.getValue(), value);
            }
            return;
        }
        if (expected.isJsonArray() && supplied.isJsonArray()) {
            if (expected.getAsJsonArray().size() > 0) {
                for (JsonElement item : supplied.getAsJsonArray()) {
                    checkTypes(key, expected.getAsJsonArray().get(0), item);
                }
            }
            return;
        }
        if (expected.isJsonPrimitive() && supplied.isJsonPrimitive()
                && expected.getAsJsonPrimitive().isBoolean() == supplied.getAsJsonPrimitive().isBoolean()
                && expected.getAsJsonPrimitive().isNumber() == supplied.getAsJsonPrimitive().isNumber()) return;
        throw new IllegalArgumentException("Incorrect value type for " + key.substring(1));
    }

    public void save(T config) {
        try {
            Path configPath = getConfigPath();
            AtomicFileIO.writeUtf8Atomic(configPath, writer -> GSON.toJson(config, writer));
        } catch (IOException e) {
            throw new RuntimeException("Could not save config: " + configFileName, e);
        }
    }
}
