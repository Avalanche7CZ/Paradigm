package eu.avalanche7.paradigm.modules.permissions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class PermissionNodeRegistry {
    public static final String SOURCE_PARADIGM = "paradigm";
    public static final String SOURCE_COMMAND_TREE = "brigadier_command";
    public static final String SOURCE_COMMAND_ALIAS = "brigadier_command_alias";
    public static final String SOURCE_FORGE_PERMISSION_API = "forge_permission_api";
    public static final String SOURCE_NEOFORGE_PERMISSION_API = "neoforge_permission_api";

    private static final String FILE_NAME = "paradigm/discovered_permissions.json";
    private static final int MAX_COMMAND_DEPTH = 12;

    private final Logger logger;
    private final DebugLogger debugLogger;
    private final IConfig config;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Object lock = new Object();
    private RegistryState state = new RegistryState();
    private final Map<String, ExternalNode> externalNodes = new LinkedHashMap<>();

    public PermissionNodeRegistry(Logger logger, DebugLogger debugLogger, IConfig config) {
        this.logger = logger;
        this.debugLogger = debugLogger;
        this.config = config;
        load();
    }

    public void registerNode(String node, String source, String description, int defaultLevel) {
        String normalized = normalizeNode(node);
        if (normalized == null) {
            return;
        }

        synchronized (lock) {
            DiscoveredPermission existing = state.nodes.get(normalized);
            long now = System.currentTimeMillis();
            if (existing == null) {
                existing = new DiscoveredPermission();
                existing.node = normalized;
                existing.firstSeen = now;
                state.nodes.put(normalized, existing);
            }
            existing.source = clean(source, "manual");
            existing.description = clean(description, "");
            existing.defaultLevel = defaultLevel;
            existing.lastSeen = now;
            saveLocked();
        }
    }

    public int discoverCommandTree(Object dispatcher) {
        if (dispatcher == null) {
            return 0;
        }

        Map<String, DiscoveredPermission> discovered = new TreeMap<>();
        try {
            Object root = invokeNoArg(dispatcher, "getRoot");
            if (root == null) {
                return 0;
            }
            for (Object child : children(root)) {
                collectCommandNode(child, "", discovered, 0);
            }
        } catch (Throwable t) {
            debugLogger.debugLog("[Permissions] Failed to scan command tree: " + t);
            return 0;
        }

        if (discovered.isEmpty()) {
            return 0;
        }

        synchronized (lock) {
            long now = System.currentTimeMillis();
            int changed = 0;
            for (DiscoveredPermission entry : discovered.values()) {
                DiscoveredPermission existing = state.nodes.get(entry.node);
                if (existing == null) {
                    entry.firstSeen = now;
                    entry.lastSeen = now;
                    state.nodes.put(entry.node, entry);
                    changed++;
                } else {
                    existing.source = entry.source;
                    existing.description = entry.description;
                    existing.defaultLevel = entry.defaultLevel;
                    existing.lastSeen = now;
                    changed++;
                }
            }
            removeCommandAliasNodesLocked();
            saveLocked();
            debugLogger.debugLog("[Permissions] Discovered " + discovered.size() + " command permission nodes.");
            return changed;
        }
    }

    public Map<String, String> knownNodes() {
        synchronized (lock) {
            Map<String, String> result = new LinkedHashMap<>();
            state.nodes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                    .forEach(entry -> result.put(entry.getKey(), descriptionFor(entry.getValue())));
            externalNodes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                    .forEach(entry -> result.put(entry.getKey(), descriptionFor(entry.getValue().permission)));
            return result;
        }
    }

    public List<DiscoveredPermission> listNodes(String query, int limit) {
        String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        int max = limit <= 0 ? 50 : limit;
        synchronized (lock) {
            List<DiscoveredPermission> result = new ArrayList<>();
            Map<String, DiscoveredPermission> combined = new LinkedHashMap<>(state.nodes);
            externalNodes.forEach((node, entry) -> combined.put(node, entry.permission));
            for (DiscoveredPermission entry : combined.values().stream()
                    .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.node, b.node))
                    .toList()) {
                if (!q.isEmpty() && (entry.node == null || !entry.node.toLowerCase(Locale.ROOT).contains(q))) {
                    continue;
                }
                result.add(entry.copy());
                if (result.size() >= max) {
                    break;
                }
            }
            return result;
        }
    }

    public ExternalRegistration registerExternalNode(String ownerModId, String node, String description,
                                                     int defaultLevel, String category, String featureIdentifier) {
        String owner = normalizeOwner(ownerModId);
        String normalizedNode = normalizeNode(node);
        if (owner == null || normalizedNode == null || defaultLevel < -1 || defaultLevel > 4) {
            return ExternalRegistration.inactive(ownerModId, node, ExternalRegistrationStatus.INVALID);
        }

        synchronized (lock) {
            ExternalNode existing = externalNodes.get(normalizedNode);
            if (existing != null) {
                if (!existing.owner.equals(owner) || !existing.matches(description, defaultLevel, category, featureIdentifier)) {
                    if (logger != null) {
                        logger.warn("Paradigm: external permission node '{}' from '{}' conflicts with owner '{}'.",
                                normalizedNode, owner, existing.owner);
                    }
                    return ExternalRegistration.inactive(owner, normalizedNode, ExternalRegistrationStatus.CONFLICT);
                }
                UUID token = UUID.randomUUID();
                existing.tokens.add(token);
                return registration(owner, normalizedNode, token, ExternalRegistrationStatus.ALREADY_REGISTERED);
            }

            long now = System.currentTimeMillis();
            DiscoveredPermission permission = new DiscoveredPermission();
            permission.node = normalizedNode;
            permission.source = "external:" + owner;
            permission.description = clean(description, "External permission node from " + owner + ".");
            permission.defaultLevel = defaultLevel;
            permission.category = clean(category, "");
            permission.featureIdentifier = clean(featureIdentifier, "");
            permission.firstSeen = now;
            permission.lastSeen = now;

            UUID token = UUID.randomUUID();
            ExternalNode entry = new ExternalNode(owner, permission);
            entry.tokens.add(token);
            externalNodes.put(normalizedNode, entry);
            return registration(owner, normalizedNode, token, ExternalRegistrationStatus.REGISTERED);
        }
    }

    public void clearExternalNodes() {
        synchronized (lock) {
            externalNodes.clear();
        }
    }

    private ExternalRegistration registration(String owner, String node, UUID token, ExternalRegistrationStatus status) {
        AtomicBoolean active = new AtomicBoolean(true);
        return new ExternalRegistration(owner, node, status, active, () -> {
            if (!active.compareAndSet(true, false)) return;
            synchronized (lock) {
                ExternalNode entry = externalNodes.get(node);
                if (entry == null || !entry.owner.equals(owner)) return;
                entry.tokens.remove(token);
                if (entry.tokens.isEmpty()) externalNodes.remove(node);
            }
        });
    }

    public Set<String> commandCandidates(String commandLine) {
        List<String> tokens = commandTokens(commandLine);
        Set<String> candidates = new LinkedHashSet<>();
        if (tokens.isEmpty()) {
            return candidates;
        }

        int max = Math.min(tokens.size(), MAX_COMMAND_DEPTH);
        for (int length = max; length >= 1; length--) {
            String path = String.join(".", tokens.subList(0, length));
            candidates.add("command." + path);
            candidates.add(path);
        }
        return candidates;
    }

    public static List<String> commandTokens(String commandLine) {
        String normalized = commandLine != null ? commandLine.trim() : "";
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.isBlank()) {
            return List.of();
        }

        String[] raw = normalized.split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String value : raw) {
            String token = normalizePathSegment(value);
            if (token == null) {
                break;
            }
            tokens.add(token);
            if (tokens.size() >= MAX_COMMAND_DEPTH) {
                break;
            }
        }
        return tokens;
    }

    private void collectCommandNode(Object node, String prefix, Map<String, DiscoveredPermission> discovered, int depth) {
        if (node == null || depth > MAX_COMMAND_DEPTH) {
            return;
        }
        if (depth > 0 && isArgumentNode(node)) {
            return;
        }

        String segment = nodeName(node);
        String cleanSegment = normalizePathSegment(segment);
        if (cleanSegment == null) {
            return;
        }

        String path = prefix.isBlank() ? cleanSegment : prefix + "." + cleanSegment;
        addDiscovered(discovered, "command." + path, SOURCE_COMMAND_TREE, "Brigadier command permission for /" + path.replace('.', ' '), -1);

        for (Object child : children(node)) {
            collectCommandNode(child, path, discovered, depth + 1);
        }
    }

    private void addDiscovered(Map<String, DiscoveredPermission> discovered, String node, String source, String description, int defaultLevel) {
        String normalized = normalizeNode(node);
        if (normalized == null) {
            return;
        }
        DiscoveredPermission entry = new DiscoveredPermission();
        entry.node = normalized;
        entry.source = source;
        entry.description = description;
        entry.defaultLevel = defaultLevel;
        discovered.put(normalized, entry);
    }

    private static String descriptionFor(DiscoveredPermission entry) {
        if (entry == null) {
            return "";
        }
        String description = entry.description != null ? entry.description : "";
        if (entry.source == null || entry.source.isBlank()) {
            return description;
        }
        return description.isBlank() ? "Discovered permission node (" + entry.source + ")." : description + " [" + entry.source + "]";
    }

    private static boolean isArgumentNode(Object node) {
        String className = node.getClass().getName();
        return className.contains("ArgumentCommandNode");
    }

    private static Collection<?> children(Object node) {
        Object value = invokeNoArg(node, "getChildren");
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        return List.of();
    }

    private static String nodeName(Object node) {
        Object name = invokeNoArg(node, "getLiteral");
        if (name == null) {
            name = invokeNoArg(node, "getName");
        }
        if (name == null) {
            name = invokeNoArg(node, "getUsageText");
        }
        return name != null ? String.valueOf(name) : null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void load() {
        Path path = resolvePath();
        if (path == null || !Files.exists(path)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RegistryState loaded = gson.fromJson(reader, RegistryState.class);
            if (loaded != null) {
                loaded.normalize();
                synchronized (lock) {
                    this.state = loaded;
                    removeCommandAliasNodesLocked();
                    saveLocked();
                }
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("Paradigm: Failed to load discovered_permissions.json, using defaults. {}", t.getMessage());
            }
            save();
        }
    }

    public void save() {
        synchronized (lock) {
            saveLocked();
        }
    }

    private void saveLocked() {
        Path path = resolvePath();
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                gson.toJson(state, writer);
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("Paradigm: Failed to save discovered_permissions.json: {}", t.getMessage());
            }
        }
    }

    private Path resolvePath() {
        return config != null ? config.resolveConfigPath(FILE_NAME) : null;
    }

    private void removeCommandAliasNodesLocked() {
        if (state == null || state.nodes == null || state.nodes.isEmpty()) {
            return;
        }
        state.nodes.entrySet().removeIf(entry -> {
            DiscoveredPermission permission = entry.getValue();
            return permission != null && SOURCE_COMMAND_ALIAS.equals(permission.source);
        });
    }

    private static String normalizeNode(String node) {
        String normalized = node != null ? node.trim().toLowerCase(Locale.ROOT) : "";
        if (normalized.startsWith("-")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.isBlank() || normalized.contains(" ")) {
            return null;
        }
        return normalized;
    }

    private static String normalizeOwner(String ownerModId) {
        String owner = ownerModId != null ? ownerModId.trim().toLowerCase(Locale.ROOT) : "";
        return owner.matches("[a-z0-9_.-]+") ? owner : null;
    }

    private static String normalizePathSegment(String segment) {
        String cleaned = segment != null ? segment.trim().toLowerCase(Locale.ROOT) : "";
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.startsWith("<") || cleaned.endsWith(">")) {
            return null;
        }
        cleaned = cleaned.replaceAll("[^a-z0-9_:\\-.]", "");
        if (cleaned.isBlank()) {
            return null;
        }
        return cleaned.replace(':', '.');
    }

    private static String clean(String value, String fallback) {
        String cleaned = value != null ? value.trim() : "";
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    public static class RegistryState {
        public int version = 1;
        public Map<String, DiscoveredPermission> nodes = new LinkedHashMap<>();

        void normalize() {
            if (nodes == null) {
                nodes = new LinkedHashMap<>();
                return;
            }
            Map<String, DiscoveredPermission> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, DiscoveredPermission> entry : nodes.entrySet()) {
                DiscoveredPermission value = entry.getValue();
                String node = normalizeNode(value != null && value.node != null ? value.node : entry.getKey());
                if (node == null) {
                    continue;
                }
                if (value == null) {
                    value = new DiscoveredPermission();
                }
                value.node = node;
                value.source = clean(value.source, "manual");
                value.description = clean(value.description, "");
                normalized.put(node, value);
            }
            nodes = normalized;
        }
    }

    public static class DiscoveredPermission {
        public String node;
        public String source;
        public String description;
        public int defaultLevel = -1;
        public long firstSeen;
        public long lastSeen;
        public String category = "";
        public String featureIdentifier = "";

        public DiscoveredPermission copy() {
            DiscoveredPermission copy = new DiscoveredPermission();
            copy.node = this.node;
            copy.source = this.source;
            copy.description = this.description;
            copy.defaultLevel = this.defaultLevel;
            copy.firstSeen = this.firstSeen;
            copy.lastSeen = this.lastSeen;
            copy.category = this.category;
            copy.featureIdentifier = this.featureIdentifier;
            return copy;
        }

        public String lastSeenIso() {
            return lastSeen > 0 ? Instant.ofEpochMilli(lastSeen).toString() : "";
        }
    }

    public enum ExternalRegistrationStatus { REGISTERED, ALREADY_REGISTERED, CONFLICT, INVALID }

    public record ExternalRegistration(String owner, String node, ExternalRegistrationStatus status,
                                       AtomicBoolean active, Runnable closeAction) implements AutoCloseable {
        static ExternalRegistration inactive(String owner, String node, ExternalRegistrationStatus status) {
            return new ExternalRegistration(owner != null ? owner : "", node != null ? node : "", status,
                    new AtomicBoolean(false), () -> { });
        }

        public boolean isActive() {
            return active.get();
        }

        @Override
        public void close() {
            closeAction.run();
        }
    }

    private static final class ExternalNode {
        private final String owner;
        private final DiscoveredPermission permission;
        private final Set<UUID> tokens = new LinkedHashSet<>();

        private ExternalNode(String owner, DiscoveredPermission permission) {
            this.owner = owner;
            this.permission = permission;
        }

        private boolean matches(String description, int level, String category, String featureIdentifier) {
            return permission.description.equals(clean(description, "External permission node from " + owner + "."))
                    && permission.defaultLevel == level
                    && permission.category.equals(clean(category, ""))
                    && permission.featureIdentifier.equals(clean(featureIdentifier, ""));
        }
    }
}
