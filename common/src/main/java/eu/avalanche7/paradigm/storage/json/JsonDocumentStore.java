package eu.avalanche7.paradigm.storage.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

public class JsonDocumentStore {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final IConfig config;
    private final Logger logger;

    public JsonDocumentStore(IConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public <T> T load(String relativePath, Class<T> type, Supplier<T> fallback) {
        Path path = path(relativePath);
        if (path == null || !Files.exists(path)) {
            return fallback.get();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T loaded = gson.fromJson(reader, type);
            return loaded != null ? loaded : fallback.get();
        } catch (Throwable t) {
            if (logger != null) logger.warn("Paradigm storage: failed to load {}: {}", relativePath, t.getMessage());
            return fallback.get();
        }
    }

    public void save(String relativePath, Object value) {
        Path path = path(relativePath);
        if (path == null || value == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                gson.toJson(value, writer);
            }
        } catch (Throwable t) {
            if (logger != null) logger.warn("Paradigm storage: failed to save {}: {}", relativePath, t.getMessage());
        }
    }

    private Path path(String relativePath) {
        return config != null ? config.resolveConfigPath(relativePath) : null;
    }
}
