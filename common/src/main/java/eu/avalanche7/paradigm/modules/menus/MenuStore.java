package eu.avalanche7.paradigm.modules.menus;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import eu.avalanche7.paradigm.utils.AtomicFileIO;

public final class MenuStore {

    public static final int MAX_MENUS = 500;
    public static final String EXTENSION = ".json";

    public record LoadResult(Map<Path, MenuDefinition> files, List<String> errors) {
        public List<MenuDefinition> definitions() {
            return List.copyOf(files.values());
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Object lock = new Object();
    private final Supplier<Path> directorySupplier;
    private volatile Map<Path, MenuDefinition> lastGoodFiles = Map.of();

    public MenuStore(Supplier<Path> directorySupplier) {
        this.directorySupplier = directorySupplier;
    }

    public Path directory() {
        return directorySupplier.get();
    }

    public LoadResult loadAll() {
        Map<Path, MenuDefinition> definitions = new java.util.LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        Path directory = directory();
        var previous = lastGoodFiles;

        synchronized (lock) {
            if (!Files.isDirectory(directory)) {
                try {
                    Files.createDirectories(directory);
                } catch (IOException failure) {
                    errors.add("Could not create menus directory: " + failure.getMessage());
                }
                return new LoadResult(errors.isEmpty() ? definitions : previous, errors);
            }

            List<Path> files = new ArrayList<>();
            try (Stream<Path> stream = Files.list(directory)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION))
                        .sorted()
                        .forEach(files::add);
            } catch (IOException failure) {
                errors.add("Could not read menus directory: " + failure.getMessage());
                return new LoadResult(previous, errors);
            }

            Set<String> seen = new HashSet<>();
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                if (definitions.size() >= MAX_MENUS) {
                    errors.add("Menu limit of " + MAX_MENUS + " reached; skipping " + fileName + ".");
                    continue;
                }
                MenuDefinition definition;
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    definition = gson.fromJson(reader, MenuDefinition.class);
                } catch (JsonParseException malformed) {
                    errors.add(fileName + ": malformed JSON (" + rootMessage(malformed) + ")");
                    keepLastGood(file, previous, definitions, seen);
                    continue;
                } catch (IOException failure) {
                    errors.add(fileName + ": could not be read (" + failure.getMessage() + ")");
                    keepLastGood(file, previous, definitions, seen);
                    continue;
                }
                if (definition == null) {
                    errors.add(fileName + ": file is empty.");
                    keepLastGood(file, previous, definitions, seen);
                    continue;
                }
                if (definition.id == null || definition.id.isBlank()) {
                    definition.id = stripExtension(fileName);
                }
                try {
                    definition.normalize();
                } catch (RuntimeException invalid) {
                    errors.add(fileName + ": " + invalid.getMessage());
                    keepLastGood(file, previous, definitions, seen);
                    continue;
                }
                if (!seen.add(definition.id)) {
                    errors.add(fileName + ": duplicate menu id '" + definition.id + "' (skipped).");
                    keepLastGood(file, previous, definitions, seen);
                    continue;
                }
                definitions.put(file, definition);
            }
        }
        return new LoadResult(java.util.Collections.unmodifiableMap(definitions), List.copyOf(errors));
    }

    void recordReload(LoadResult result) {
        lastGoodFiles = result.files();
    }

    private static void keepLastGood(Path file, Map<Path, MenuDefinition> previous,
            Map<Path, MenuDefinition> candidate, Set<String> seen) {
        MenuDefinition definition = previous.get(file);
        if (definition != null && seen.add(definition.id)) candidate.put(file, definition);
    }

    public MenuDefinition read(String id) {
        Path file = fileFor(id);
        synchronized (lock) {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                MenuDefinition definition = gson.fromJson(reader, MenuDefinition.class);
                if (definition != null) {
                    if (definition.id == null || definition.id.isBlank()) {
                        definition.id = normalizeId(id);
                    }
                    definition.normalize();
                }
                return definition;
            } catch (IOException | RuntimeException failure) {
                return null;
            }
        }
    }

    public void save(MenuDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("A menu definition is required.");
        }
        definition.normalize();
        Path file = fileFor(definition.id);
        synchronized (lock) {
            try {
                AtomicFileIO.writeUtf8Atomic(file, writer -> gson.toJson(definition, writer));
            } catch (IOException failure) {
                throw new IllegalStateException("Could not save menu '" + definition.id + "': " + failure.getMessage());
            }
        }
    }

    public boolean delete(String id) {
        Path file = fileFor(id);
        synchronized (lock) {
            try {
                return Files.deleteIfExists(file);
            } catch (IOException failure) {
                throw new IllegalStateException("Could not delete menu '" + id + "': " + failure.getMessage());
            }
        }
    }

    public boolean exists(String id) {
        return Files.isRegularFile(fileFor(id));
    }

    public Path fileFor(String id) {
        return directory().resolve(normalizeId(id) + EXTENSION);
    }

    public String toJson(MenuDefinition definition) {
        return gson.toJson(definition);
    }

    public MenuDefinition fromJson(String json) {
        MenuDefinition definition;
        try {
            definition = gson.fromJson(json, MenuDefinition.class);
        } catch (JsonParseException malformed) {
            throw new IllegalArgumentException("Malformed menu definition: " + rootMessage(malformed), malformed);
        }
        if (definition == null) {
            throw new IllegalArgumentException("The menu definition is empty.");
        }
        definition.normalize();
        return definition;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : fileName).toLowerCase(Locale.ROOT);
    }

    private static String normalizeId(String id) {
        String normalized = id != null ? id.trim().toLowerCase(Locale.ROOT) : "";
        if (!normalized.matches("^[a-z0-9][a-z0-9_-]{0,63}$")) {
            throw new IllegalArgumentException("Invalid menu id: " + id);
        }
        return normalized;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }
}
