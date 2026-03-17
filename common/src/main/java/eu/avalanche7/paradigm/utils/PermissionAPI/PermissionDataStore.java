package eu.avalanche7.paradigm.utils.PermissionAPI;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;

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

/**
 * Lightweight persistence for Paradigm's internal permission model.
 */
public class PermissionDataStore {
    private static final String FILE_NAME = "paradigm/permissions.json";

    private final Logger logger;
    private final DebugLogger debugLogger;
    private final IConfig config;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public PermissionDataStore(Logger logger, DebugLogger debugLogger, IConfig config) {
        this.logger = logger;
        this.debugLogger = debugLogger;
        this.config = config;
    }

    public PermissionState load() {
        Path path = resolvePath();
        if (path == null) {
            return PermissionState.createDefault();
        }

        if (!Files.exists(path)) {
            PermissionState defaults = PermissionState.createDefault();
            save(defaults);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            PermissionState loaded = gson.fromJson(reader, PermissionState.class);
            if (loaded == null) {
                PermissionState defaults = PermissionState.createDefault();
                save(defaults);
                return defaults;
            }
            loaded.normalize();
            return loaded;
        } catch (Exception e) {
            logger.warn("Paradigm: Failed to load permissions.json, using defaults. {}", e.getMessage());
            PermissionState defaults = PermissionState.createDefault();
            save(defaults);
            return defaults;
        }
    }

    public void save(PermissionState state) {
        Path path = resolvePath();
        if (path == null || state == null) {
            return;
        }

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                gson.toJson(state, writer);
            }
        } catch (Exception e) {
            logger.warn("Paradigm: Failed to save permissions.json: {}", e.getMessage());
        }
    }

    private Path resolvePath() {
        if (config == null) {
            debugLogger.debugLog("[PermissionAPI] Missing platform config, cannot resolve permissions path.");
            return null;
        }
        return config.resolveConfigPath(FILE_NAME);
    }

    public static class PermissionState {
        public int version = 1;
        @SerializedName(value = "default_group", alternate = {"defaultGroup"})
        public String defaultGroup = "default";
        public Map<String, GroupEntry> groups = new LinkedHashMap<>();
        public Map<String, UserEntry> users = new LinkedHashMap<>();

        public static PermissionState createDefault() {
            PermissionState state = new PermissionState();
            GroupEntry defaultGroupEntry = new GroupEntry();
            defaultGroupEntry.description = "Default group for all players";
            defaultGroupEntry.prefix = "&b[Default]&r ";
            defaultGroupEntry.suffix = " &7#New";
            defaultGroupEntry.permissions.add("paradigm.msg");
            defaultGroupEntry.permissions.add("paradigm.reply");
            state.groups.put("default", defaultGroupEntry);
            return state;
        }

        public void normalize() {
            if (defaultGroup == null || defaultGroup.isBlank()) {
                defaultGroup = "default";
            }
            defaultGroup = defaultGroup.trim().toLowerCase(Locale.ROOT);

            if (groups == null) {
                groups = new LinkedHashMap<>();
            }
            if (users == null) {
                users = new LinkedHashMap<>();
            }

            Map<String, GroupEntry> normalizedGroups = new LinkedHashMap<>();
            for (Map.Entry<String, GroupEntry> entry : groups.entrySet()) {
                String normalizedName = normalizeGroupName(entry.getKey());
                if (normalizedName == null) {
                    continue;
                }

                GroupEntry normalizedEntry = entry.getValue() != null ? entry.getValue() : new GroupEntry();
                normalizedEntry.normalize();
                normalizedGroups.put(normalizedName, normalizedEntry);
            }
            groups = normalizedGroups;

            Map<String, UserEntry> normalizedUsers = new LinkedHashMap<>();
            for (Map.Entry<String, UserEntry> entry : users.entrySet()) {
                String normalizedUuid = normalizeUuid(entry.getKey());
                if (normalizedUuid == null) {
                    continue;
                }

                UserEntry normalizedEntry = entry.getValue() != null ? entry.getValue() : new UserEntry();
                normalizedEntry.normalize();
                normalizedUsers.put(normalizedUuid, normalizedEntry);
            }
            users = normalizedUsers;

            if (!groups.containsKey(defaultGroup.toLowerCase(Locale.ROOT))) {
                groups.put(defaultGroup.toLowerCase(Locale.ROOT), new GroupEntry());
            }
        }

        private static String normalizeGroupName(String name) {
            if (name == null) {
                return null;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            return normalized.isEmpty() ? null : normalized;
        }

        private static String normalizeUuid(String uuid) {
            if (uuid == null) {
                return null;
            }
            String normalized = uuid.trim().toLowerCase(Locale.ROOT);
            return normalized.isEmpty() ? null : normalized;
        }
    }

    public static class GroupEntry {
        public String description = "";
        public String prefix = "";
        public String suffix = "";
        public int weight = 0;
        @SerializedName(value = "perms", alternate = {"permissions"})
        public List<String> permissions = new ArrayList<>();
        public List<String> inherits = new ArrayList<>();

        public void normalize() {
            if (description == null) {
                description = "";
            }
            if (prefix == null) {
                prefix = "";
            }
            if (suffix == null) {
                suffix = "";
            }
            if (permissions == null) {
                permissions = new ArrayList<>();
            }
            if (inherits == null) {
                inherits = new ArrayList<>();
            }

            permissions = normalizePermissionRules(permissions);
            inherits = normalizeStringList(inherits, true);
        }
    }

    public static class UserEntry {
        @SerializedName(value = "perms", alternate = {"permissions"})
        public List<String> permissions = new ArrayList<>();
        public List<String> groups = new ArrayList<>();

        public void normalize() {
            if (permissions == null) {
                permissions = new ArrayList<>();
            }
            if (groups == null) {
                groups = new ArrayList<>();
            }

            permissions = normalizePermissionRules(permissions);
            groups = normalizeStringList(groups, true);
        }
    }

    private static List<String> normalizePermissionRules(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }

            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            boolean denied = trimmed.startsWith("-");
            String node = denied ? trimmed.substring(1).trim() : trimmed;
            if (node.isEmpty()) {
                continue;
            }

            node = node.toLowerCase(Locale.ROOT);
            normalized.add(denied ? "-" + node : node);
        }

        return new ArrayList<>(normalized);
    }

    private static List<String> normalizeStringList(List<String> values, boolean toLowercase) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String cleaned = value.trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            if (toLowercase) {
                cleaned = cleaned.toLowerCase(Locale.ROOT);
            }
            normalized.add(cleaned);
        }

        return new ArrayList<>(normalized);
    }
}



