package eu.avalanche7.paradigm.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
        this.persistedStates = loadPersistedStates();
    }

    public synchronized void registerCommand(String commandId, boolean defaultEnabled, boolean protectedCommand, String... commandAliases) {
        String canonical = normalize(commandId);
        if (canonical == null) {
            return;
        }

        boolean enabled = persistedStates.getOrDefault(canonical, defaultEnabled);
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
            save();
        }
    }

    public boolean isEnabled(String commandOrAlias) {
        String canonical = resolve(commandOrAlias);
        if (canonical == null) {
            return true;
        }
        return commandStates.getOrDefault(canonical, true);
    }

    public synchronized ToggleResult setEnabled(String commandOrAlias, boolean enabled) {
        String canonical = resolve(commandOrAlias);
        if (canonical == null) {
            return ToggleResult.unknown(commandOrAlias);
        }
        if (!enabled && protectedCommands.contains(canonical)) {
            return ToggleResult.protectedCommand(canonical);
        }

        commandStates.put(canonical, enabled);
        save();
        return ToggleResult.success(canonical, enabled);
    }

    public synchronized void reload() {
        persistedStates = loadPersistedStates();

        for (String commandId : new ArrayList<>(commandStates.keySet())) {
            if (!registeredCommands.contains(commandId)) {
                commandStates.remove(commandId);
            }
        }

        for (String commandId : registeredCommands) {
            boolean enabled = persistedStates.getOrDefault(commandId, commandStates.getOrDefault(commandId, true));
            commandStates.put(commandId, enabled);
        }

        save();
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

    private synchronized void save() {
        Path path = resolvePath();
        if (path == null) {
            return;
        }

        CommandToggleState state = new CommandToggleState();
        state.commands = new LinkedHashMap<>(listStates());

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                gson.toJson(state, writer);
            }
        } catch (Exception e) {
            logger.warn("Paradigm: Failed to save commands.json: {}", e.getMessage());
        }
    }

    private Map<String, Boolean> loadPersistedStates() {
        Path path = resolvePath();
        if (path == null) {
            return new LinkedHashMap<>();
        }

        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            CommandToggleState loaded = gson.fromJson(reader, CommandToggleState.class);
            if (loaded == null || loaded.commands == null) {
                return new LinkedHashMap<>();
            }

            Map<String, Boolean> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Boolean> entry : loaded.commands.entrySet()) {
                String canonical = normalize(entry.getKey());
                if (canonical != null) {
                    normalized.put(canonical, Boolean.TRUE.equals(entry.getValue()));
                }
            }
            return normalized;
        } catch (Exception e) {
            logger.warn("Paradigm: Failed to load commands.json, defaults will be used. {}", e.getMessage());
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


