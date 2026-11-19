package eu.avalanche7.paradigm.utils;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import eu.avalanche7.paradigm.modules.commands.shared.CommandCatalog;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;

public class CommandToggleStore {
    private static final String FILE_NAME = "paradigm/commands.json";

    private final LoggerLike logger;
    private final DebugLogger debugLogger;
    private final IConfig config;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Map<String, Boolean> commandStates = new ConcurrentHashMap<>();
    private final Map<String, String> aliases = new ConcurrentHashMap<>();
    private final Set<String> protectedCommands = ConcurrentHashMap.newKeySet();
    private final Set<String> registeredCommands = ConcurrentHashMap.newKeySet();

    private volatile Map<String, Boolean> persistedStates = new LinkedHashMap<>();

    public CommandToggleStore(org.slf4j.Logger logger, DebugLogger debugLogger, IConfig config) {
        this.logger = new LoggerLike(logger);
        this.debugLogger = debugLogger;
        this.config = config;
        this.persistedStates = loadPersistedStates(false);
    }

    public synchronized void registerCommand(String commandId, boolean defaultEnabled, boolean protectedCommand, String... commandAliases) {
        String canonical = normalize(commandId);
        if (canonical == null) {
            return;
        }

        boolean enabled = protectedCommand || persistedStates.getOrDefault(canonical, defaultEnabled);
        Boolean previous = commandStates.putIfAbsent(canonical, enabled);
        registeredCommands.add(canonical);
        if (protectedCommand) {
            protectedCommands.add(canonical);
        }

        if (commandAliases != null) {
            for (String alias : commandAliases) {
                String normalizedAlias = normalize(alias);
                if (normalizedAlias != null) {
                    aliases.put(normalizedAlias, canonical);
                }
            }
        }

        if (previous == null && !persistedStates.containsKey(canonical)) {
            try {
                save(listStates());
            } catch (RuntimeException failure) {
                logger.warn("Paradigm: Failed to save commands.json: {}", failure.getMessage());
            }
        }
    }

    public boolean isEnabled(String commandOrAlias) {
        String canonical = resolve(commandOrAlias);
        if (canonical == null) {
            return true;
        }
        return commandStates.getOrDefault(canonical, true) && CommandCatalog.isModuleEnabled(canonical);
    }

    public synchronized ToggleResult setEnabled(String commandOrAlias, boolean enabled) {
        String canonical = resolve(commandOrAlias);
        if (canonical == null) {
            return ToggleResult.unknown(commandOrAlias);
        }
        if (!enabled && protectedCommands.contains(canonical)) {
            return ToggleResult.protectedCommand(canonical);
        }

        Map<String, Boolean> candidate = readReload();
        candidate.put(canonical, enabled);
        save(candidate);
        recordSaved(candidate);
        publish(candidate);
        return ToggleResult.success(canonical, enabled);
    }

    public Map<String, Boolean> readReload() {
        return overlaySavedStates(loadPersistedStates(true));
    }

    public Map<String, Boolean> reviewedStates() {
        return overlaySavedStates(persistedStates);
    }

    private Map<String, Boolean> overlaySavedStates(Map<String, Boolean> saved) {
        Map<String, Boolean> candidate = listStates();
        saved.forEach((id, enabled) -> {
            if (candidate.containsKey(id)) candidate.put(id, isProtected(id) || enabled);
        });
        return candidate;
    }

    public void recordSaved(Map<String, Boolean> candidate) {
        persistedStates = Map.copyOf(candidate);
    }

    public synchronized void publish(Map<String, Boolean> candidate) {
        commandStates.putAll(candidate);
    }

    public Map<String, Boolean> listStates() {
        Map<String, Boolean> sorted = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(commandStates.keySet());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        for (String key : keys) {
            sorted.put(key, commandStates.getOrDefault(key, true));
        }
        return sorted;
    }

    public List<String> knownCommandIds() {
        List<String> ids = new ArrayList<>(commandStates.keySet());
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }

    public String resolveCanonical(String commandOrAlias) {
        return resolve(commandOrAlias);
    }

    public boolean isProtected(String commandOrAlias) {
        String canonical = resolve(commandOrAlias);
        return canonical != null && protectedCommands.contains(canonical);
    }

    public synchronized void save(Map<String, Boolean> candidate) {
        Path path = resolvePath();
        if (path == null) throw new IllegalStateException("Command toggle storage is unavailable.");

        CommandToggleState state = new CommandToggleState();
        state.commands = new LinkedHashMap<>(candidate);

        try {
            AtomicFileIO.writeUtf8Atomic(path, writer -> gson.toJson(state, writer));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not save commands.json", failure);
        }
    }

    private Map<String, Boolean> loadPersistedStates(boolean strict) {
        Path path = resolvePath();
        if (path == null || Files.notExists(path)) return new LinkedHashMap<>();

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var json = com.google.gson.JsonParser.parseReader(reader);
            if (!json.isJsonObject()) throw new IllegalArgumentException("Expected a command toggle object.");
            var commands = json.getAsJsonObject().get("commands");
            if (commands == null) return new LinkedHashMap<>();

            Map<String, Boolean> normalized = new LinkedHashMap<>();
            for (var entry : commands.getAsJsonObject().entrySet()) {
                String canonical = normalize(entry.getKey());
                var value = entry.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("Invalid command toggle for " + entry.getKey());
                }
                if (canonical != null) normalized.put(canonical, value.getAsBoolean());
            }
            return normalized;
        } catch (java.io.IOException | RuntimeException failure) {
            if (strict) throw new IllegalArgumentException("Could not read commands.json: " + failure.getMessage(), failure);
            Path archived = AtomicFileIO.quarantine(path, "corrupt");
            if (archived != null) {
                logger.warn("Paradigm: Failed to load commands.json; archived unreadable data as {}", archived.getFileName());
            } else {
                logger.warn("Paradigm: Failed to load commands.json and could not archive it: {}", failure.getMessage());
            }
            return new LinkedHashMap<>();
        }
    }

    private Path resolvePath() {
        if (config == null) {
            if (debugLogger != null) {
                debugLogger.debugLog("[CommandToggleStore] Missing platform config, cannot resolve commands path.");
            }
            return null;
        }
        return config.resolveConfigPath(FILE_NAME);
    }

    private String resolve(String commandOrAlias) {
        String normalized = normalize(commandOrAlias);
        if (normalized == null) {
            return null;
        }
        if (registeredCommands.contains(normalized) && commandStates.containsKey(normalized)) {
            return normalized;
        }
        String aliasTarget = aliases.get(normalized);
        if (aliasTarget != null && registeredCommands.contains(aliasTarget)) {
            return aliasTarget;
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public record ToggleResult(boolean ok, String canonicalId, String reason, boolean enabled) {
        private static ToggleResult success(String canonicalId, boolean enabled) {
            return new ToggleResult(true, canonicalId, "", enabled);
        }

        private static ToggleResult unknown(String input) {
            return new ToggleResult(false, input, "unknown", false);
        }

        private static ToggleResult protectedCommand(String canonicalId) {
            return new ToggleResult(false, canonicalId, "protected", false);
        }
    }

    private static class CommandToggleState {
        int version = 1;
        Map<String, Boolean> commands = new LinkedHashMap<>();
    }

    private static final class LoggerLike {
        private final org.slf4j.Logger logger;

        private LoggerLike(org.slf4j.Logger logger) {
            this.logger = logger;
        }

        private void warn(String message, Object arg) {
            if (logger != null) {
                logger.warn(message, arg);
            }
        }
    }
}
