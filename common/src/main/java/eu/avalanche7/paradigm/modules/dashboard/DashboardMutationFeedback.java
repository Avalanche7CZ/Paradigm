package eu.avalanche7.paradigm.modules.dashboard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import eu.avalanche7.paradigm.configs.schema.ConfigField;
import eu.avalanche7.paradigm.configs.schema.ConfigPatchOperation;
import eu.avalanche7.paradigm.configs.schema.ConfigSnapshot;
import eu.avalanche7.paradigm.configs.schema.RemoteConfigField;
import eu.avalanche7.paradigm.configs.schema.RemoteConfigSnapshot;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.permissions.PermissionMutationRequest;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class DashboardMutationFeedback {
    private static final int MAX_VISIBLE_CHANGES = 5;
    private static final int MAX_VALUE_LENGTH = 96;

    private DashboardMutationFeedback() {
    }

    public enum Area {
        CONFIG,
        PERMISSIONS,
        STORAGE,
        DISCORD,
        NETWORK_CONFIG,
        CUSTOM_COMMANDS,
        HOLOGRAMS,
        MODERATION
    }

    public enum Kind {
        PAIR,
        ADD,
        REMOVE,
        INFO
    }

    public record Change(Kind kind, String label, Object before, Object after, boolean masked, String hover) {
    }

    public static Change pair(String label, Object before, Object after) {
        return pair(label, before, after, false, "");
    }

    public static Change pair(String label, Object before, Object after, boolean masked, String hover) {
        return new Change(Kind.PAIR, safe(label), before, after, masked, safe(hover));
    }

    public static Change add(String label) {
        return add(label, "");
    }

    public static Change add(String label, String hover) {
        return new Change(Kind.ADD, safe(label), null, null, false, safe(hover));
    }

    public static Change remove(String label) {
        return remove(label, "");
    }

    public static Change remove(String label, String hover) {
        return new Change(Kind.REMOVE, safe(label), null, null, false, safe(hover));
    }

    public static Change info(String label) {
        return info(label, "");
    }

    public static Change info(String label, String hover) {
        return new Change(Kind.INFO, safe(label), null, null, false, safe(hover));
    }

    public static void notify(Services services, DashboardPrincipal principal, String requestedLocale,
                              Area area, Collection<Change> rawChanges) {
        if (services == null || principal == null || principal.console()
                || principal.uuid() == null || principal.uuid().isBlank()
                || rawChanges == null || rawChanges.isEmpty()) {
            return;
        }
        List<Change> changes = rawChanges.stream()
                .filter(Objects::nonNull)
                .filter(change -> change.label() != null && !change.label().isBlank())
                .filter(change -> change.kind() != Kind.PAIR || !Objects.equals(change.before(), change.after()))
                .toList();
        if (changes.isEmpty()) {
            return;
        }
        IPlatformAdapter platform = services.getPlatformAdapter();
        if (platform == null) {
            return;
        }
        String locale = resolveLocale(services, requestedLocale);
        try {
            platform.executeOnServerThread(() -> {
                try {
                    sendOnServerThread(services, principal, locale, area, changes);
                } catch (Throwable failure) {
                    logFailure(services, failure);
                }
            });
        } catch (Throwable failure) {
            logFailure(services, failure);
        }
    }

    public static void notifyConfigPatch(Services services, DashboardPrincipal principal, String requestedLocale,
                                         ConfigSnapshot before, ConfigSnapshot after,
                                         List<ConfigPatchOperation> requestedOperations, List<String> acceptedKeys) {
        if (before == null || after == null || acceptedKeys == null || acceptedKeys.isEmpty()) {
            return;
        }
        Map<String, ConfigField> beforeFields = indexConfig(before);
        Map<String, ConfigField> afterFields = indexConfig(after);
        Map<String, ConfigPatchOperation> requested = new LinkedHashMap<>();
        if (requestedOperations != null) {
            for (ConfigPatchOperation operation : requestedOperations) {
                if (operation != null && operation.key() != null) {
                    requested.put(operation.key(), operation);
                }
            }
        }

        List<Change> changes = new ArrayList<>();
        for (String key : new LinkedHashSet<>(acceptedKeys)) {
            if (key == null || key.isBlank()) {
                continue;
            }
            ConfigField oldField = beforeFields.get(key);
            ConfigField newField = afterFields.get(key);
            Object oldValue = fieldValue(oldField);
            Object newValue = fieldValue(newField);
            ConfigPatchOperation operation = requested.get(key);

            if (operation != null && Objects.equals(oldValue, newValue) && !Objects.equals(oldValue, operation.value())) {
                newValue = operation.value();
            }
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }

            ConfigField displayField = newField != null ? newField : oldField;
            boolean masked = (displayField != null && displayField.masked()) || sensitiveKey(key);
            changes.add(pair(configLabel(displayField, key), oldValue, newValue, masked, configHover(displayField, key)));
        }
        notify(services, principal, requestedLocale, Area.CONFIG, changes);
    }

    public static void notifyRemoteConfigPatch(Services services, DashboardPrincipal principal, String requestedLocale,
                                               RemoteConfigSnapshot before, RemoteConfigPatchRequest request,
                                               List<String> acceptedKeys) {
        if (request == null || request.operations == null || acceptedKeys == null || acceptedKeys.isEmpty()) {
            return;
        }
        Map<String, RemoteConfigField> fields = new LinkedHashMap<>();
        if (before != null && before.fields() != null) {
            for (RemoteConfigField field : before.fields()) {
                if (field != null && field.key() != null) {
                    fields.put(field.key(), field);
                }
            }
        }
        Set<String> accepted = new LinkedHashSet<>(acceptedKeys);
        boolean networkScope = "NETWORK".equalsIgnoreCase(request.scope);
        String target = networkScope ? "network" : safe(request.serverId);
        List<Change> changes = new ArrayList<>();
        for (ConfigPatchOperation operation : request.operations) {
            if (operation == null || operation.key() == null || !accepted.contains(operation.key())) {
                continue;
            }
            RemoteConfigField field = fields.get(operation.key());
            Object oldValue = null;
            if (field != null) {
                var old = networkScope ? field.networkValue() : field.serverValue();
                if (old != null && old.set()) {
                    oldValue = old.value();
                }
            }
            Object newValue = operation.value();
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            String label = target + " · " + (field != null ? configLabel(field.label(), operation.key()) : operation.key());
            String hover = operation.key()
                    + "\nscope: " + (networkScope ? "NETWORK" : "SERVER")
                    + "\nsection: " + safe(request.section)
                    + (field != null ? "\norigin before: " + safe(field.origin()) : "");
            changes.add(pair(label, oldValue, newValue,
                    (field != null && field.masked()) || sensitiveKey(operation.key()), hover));
        }
        notify(services, principal, requestedLocale, Area.NETWORK_CONFIG, changes);
    }

    public static void notifyPermissionMutation(Services services, DashboardPrincipal principal, String requestedLocale,
                                                PermissionMutationRequest request, Object beforeGroupSnapshot) {
        if (request == null || request.action == null) {
            return;
        }
        String action = safe(request.action).toLowerCase(Locale.ROOT);
        String group = safe(request.group);
        String parent = safe(request.parent);
        String user = safe(request.user);
        String permission = safe(request.permission);
        boolean prefixedDeny = permission.startsWith("-");
        boolean denied = Boolean.TRUE.equals(request.denied) || prefixedDeny;
        if (prefixedDeny) {
            permission = permission.substring(1);
        }
        boolean removal = action.endsWith("_remove");
        String permissionRule;
        if (permission.isBlank()) {
            permissionRule = !safe(request.assignmentId).isBlank()
                    ? "assignment " + safe(request.assignmentId)
                    : "permission assignment";
        } else if (!removal || request.denied != null || prefixedDeny) {
            permissionRule = permission + " = " + (denied ? "DENY" : "ALLOW");
        } else {
            permissionRule = permission;
        }
        String hover = permissionHover(request);

        List<Change> changes = new ArrayList<>();
        switch (action) {
            case "group_create" -> changes.add(add("group:" + group));
            case "group_delete" -> changes.add(remove("group:" + group));
            case "group_update" -> {
                Map<String, Object> beforeGroup = extractGroup(beforeGroupSnapshot);
                if (request.metadata != null) {
                    for (Map.Entry<String, String> entry : request.metadata.entrySet()) {
                        String field = safe(entry.getKey()).toLowerCase(Locale.ROOT);
                        Object rawOldValue = beforeGroup.get(field);
                        Object oldValue = rawOldValue != null ? String.valueOf(rawOldValue) : null;
                        Object newValue = entry.getValue() != null ? entry.getValue() : "";
                        if (!Objects.equals(oldValue, newValue)) {
                            changes.add(pair("group:" + group + " · " + field, oldValue, newValue));
                        }
                    }
                }
            }
            case "group_permission_add" ->
                    changes.add(add("group:" + group + " · " + permissionRule, hover));
            case "group_permission_remove" ->
                    changes.add(remove("group:" + group + " · " + permissionRule, hover));
            case "group_parent_add" ->
                    changes.add(add("group:" + group + " → parent:" + parent));
            case "group_parent_remove" ->
                    changes.add(remove("group:" + group + " → parent:" + parent));
            case "user_permission_add" ->
                    changes.add(add("user:" + user + " · " + permissionRule, hover));
            case "user_permission_remove" ->
                    changes.add(remove("user:" + user + " · " + permissionRule, hover));
            case "user_group_add" ->
                    changes.add(add("user:" + user + " → group:" + group, hover));
            case "user_group_remove" ->
                    changes.add(remove("user:" + user + " → group:" + group, hover));
            default -> changes.add(info(action.replace('_', ' ')));
        }
        notify(services, principal, requestedLocale, Area.PERMISSIONS, changes);
    }

    public static List<Change> diffMap(String prefix, Map<String, ?> before, Map<String, ?> after) {
        Map<String, ?> left = before != null ? before : Map.of();
        Map<String, ?> right = after != null ? after : Map.of();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(left.keySet());
        keys.addAll(right.keySet());
        List<Change> changes = new ArrayList<>();
        for (String key : keys) {
            Object oldValue = left.get(key);
            Object newValue = right.get(key);
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            String label = safe(prefix) + key;
            changes.add(pair(label, oldValue, newValue, sensitiveKey(key), ""));
        }
        return changes;
    }

    private static void sendOnServerThread(Services services, DashboardPrincipal principal, String locale,
                                           Area area, List<Change> changes) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        IPlayer player;
        try {
            player = platform.getPlayerByUuid(principal.uuid());
        } catch (Throwable ignored) {
            return;
        }
        if (player == null) {
            return;
        }

        int visible = Math.min(MAX_VISIBLE_CHANGES, changes.size());
        IComponent message = platform.createEmptyComponent()
                .append(platform.createComponentFromLiteral("◆ ").withColorHex("38BDF8"))
                .append(platform.createComponentFromLiteral("Paradigm Dashboard")
                        .withColorHex("38BDF8").withFormatting("bold"))
                .append(platform.createComponentFromLiteral("  " + areaName(locale, area))
                        .withColorHex("FBBF24").withFormatting("bold"))
                .append(platform.createComponentFromLiteral("  ·  " + countText(locale, changes.size()))
                        .withColorHex("94A3B8"))
                .append(platform.createComponentFromLiteral("\n────────────────────────")
                        .withColorHex("475569"));

        for (int i = 0; i < visible; i++) {
            appendChange(platform, message, locale, changes.get(i));
        }
        if (changes.size() > visible) {
            message.append(platform.createComponentFromLiteral("\n  … "
                            + moreText(locale, changes.size() - visible))
                    .withColorHex("94A3B8"));
        }
        platform.sendSystemMessage(player, message);
    }

    private static void appendChange(IPlatformAdapter platform, IComponent message, String locale, Change change) {
        String hover = safe(change.hover());
        switch (change.kind()) {
            case PAIR -> {
                IComponent label = platform.createComponentFromLiteral("\n" + safe(change.label()))
                        .withColorHex("E2E8F0").withFormatting("bold");
                if (!hover.isBlank()) {
                    label = label.onHoverText(hover);
                }
                message.append(label);
                boolean masked = change.masked() || sensitiveKey(change.label());
                message.append(platform.createComponentFromLiteral("\n  − "
                                + displayValue(locale, change.before(), masked))
                        .withColorHex("F87171"));
                message.append(platform.createComponentFromLiteral("\n  + "
                                + displayValue(locale, change.after(), masked))
                        .withColorHex("4ADE80"));
            }
            case ADD -> message.append(withHover(
                    platform.createComponentFromLiteral("\n  + " + safe(change.label())).withColorHex("4ADE80"),
                    hover));
            case REMOVE -> message.append(withHover(
                    platform.createComponentFromLiteral("\n  − " + safe(change.label())).withColorHex("F87171"),
                    hover));
            case INFO -> message.append(withHover(
                    platform.createComponentFromLiteral("\n  ~ " + safe(change.label())).withColorHex("FBBF24"),
                    hover));
        }
    }

    private static IComponent withHover(IComponent component, String hover) {
        return hover == null || hover.isBlank() ? component : component.onHoverText(hover);
    }

    private static Map<String, ConfigField> indexConfig(ConfigSnapshot snapshot) {
        Map<String, ConfigField> result = new LinkedHashMap<>();
        if (snapshot.fields() != null) {
            for (ConfigField field : snapshot.fields()) {
                if (field != null && field.key() != null) {
                    result.put(field.key(), field);
                }
            }
        }
        return result;
    }

    private static Object fieldValue(ConfigField field) {
        return field != null && field.value() != null && field.value().set() ? field.value().value() : null;
    }

    private static Map<String, Object> extractGroup(Object snapshot) {
        if (!(snapshot instanceof Map<?, ?> root)) {
            return Map.of();
        }
        Object group = root.get("group");
        if (!(group instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key).toLowerCase(Locale.ROOT), value);
            }
        });
        return result;
    }

    private static String configLabel(ConfigField field, String key) {
        return field != null ? configLabel(field.label(), key) : key;
    }

    private static String configLabel(String label, String key) {
        String value = safe(label);
        return value.isBlank() ? safe(key) : value;
    }

    private static String configHover(ConfigField field, String key) {
        if (field == null) {
            return key;
        }
        StringBuilder hover = new StringBuilder();
        if (field.label() != null && !field.label().isBlank()) {
            hover.append(field.label());
        }
        if (field.help() != null && !field.help().isBlank()) {
            if (!hover.isEmpty()) hover.append('\n');
            hover.append(field.help());
        }
        if (!hover.isEmpty()) hover.append('\n');
        hover.append(key);
        if (field.owner() != null && !field.owner().isBlank()) {
            hover.append("\nfile: ").append(field.owner());
        }
        return hover.toString();
    }

    private static String permissionHover(PermissionMutationRequest request) {
        StringBuilder hover = new StringBuilder();
        String assignmentId = safe(request.assignmentId);
        String scope = safe(request.scope);
        boolean hasExplicitContext = !scope.isBlank() || (request.contexts != null && !request.contexts.isEmpty());
        if (assignmentId.isBlank() || hasExplicitContext) {
            hover.append("scope: ").append(scope.isBlank() ? "GLOBAL" : scope.toUpperCase(Locale.ROOT));
        }
        if (request.contexts != null && !request.contexts.isEmpty()) {
            hover.append("\ncontexts: ");
            boolean first = true;
            for (Map.Entry<String, String> entry : request.contexts.entrySet()) {
                if (!first) hover.append(", ");
                hover.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
        }
        if (Boolean.TRUE.equals(request.permanent)) {
            appendHoverLine(hover, "expires: permanent");
        } else if (request.expiresAtMs != null) {
            try {
                appendHoverLine(hover, "expires: " + Instant.ofEpochMilli(request.expiresAtMs));
            } catch (Throwable ignored) {
                appendHoverLine(hover, "expires: " + request.expiresAtMs);
            }
        } else if (!safe(request.duration).isBlank()) {
            appendHoverLine(hover, "duration: " + safe(request.duration));
        }
        if (!assignmentId.isBlank()) {
            appendHoverLine(hover, "assignment: " + assignmentId);
        }
        return hover.toString();
    }

    private static void appendHoverLine(StringBuilder hover, String line) {
        if (line == null || line.isBlank()) return;
        if (!hover.isEmpty()) hover.append('\n');
        hover.append(line);
    }

    private static String displayValue(String locale, Object value, boolean masked) {
        if (masked) {
            return tr(locale, "<hidden>", "<skryto>", "<скрыто>");
        }
        if (value == null) {
            return tr(locale, "<unset>", "<nenastaveno>", "<не задано>");
        }
        String rendered = String.valueOf(value).replace("\r", "\\r").replace("\n", "\\n");
        if (rendered.length() > MAX_VALUE_LENGTH) {
            rendered = rendered.substring(0, MAX_VALUE_LENGTH - 1) + "…";
        }
        return rendered;
    }

    private static boolean sensitiveKey(String key) {
        String normalized = safe(key).toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("password")
                || normalized.contains("bottoken")
                || normalized.endsWith(".token")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("authtoken")
                || normalized.contains("apikey")
                || normalized.contains("privatekey")
                || normalized.contains("webhookurl");
    }

    private static void logFailure(Services services, Throwable failure) {
        try {
            if (services != null && services.getLogger() != null && failure != null) {
                services.getLogger().debug("Paradigm Dashboard: in-game mutation feedback failed: {}", failure.toString());
            }
        } catch (Throwable ignored) {
        }
    }

    private static String resolveLocale(Services services, String requested) {
        String normalized = safe(requested).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("cs")) return "cs";
        if (normalized.startsWith("ru")) return "ru";
        if (normalized.startsWith("en")) return "en";
        try {
            String configured = services.getLang() != null ? services.getLang().getCurrentLanguage() : null;
            if (configured != null) {
                String language = configured.toLowerCase(Locale.ROOT);
                if (language.startsWith("cs")) return "cs";
                if (language.startsWith("ru")) return "ru";
            }
        } catch (Throwable ignored) {
        }
        return "en";
    }

    private static String areaName(String locale, Area area) {
        return switch (area) {
            case CONFIG -> tr(locale, "CONFIG", "KONFIGURACE", "КОНФИГ");
            case PERMISSIONS -> tr(locale, "PERMISSIONS", "OPRÁVNĚNÍ", "ПРАВА");
            case STORAGE -> tr(locale, "STORAGE", "ÚLOŽIŠTĚ", "ХРАНИЛИЩЕ");
            case DISCORD -> "DISCORD";
            case NETWORK_CONFIG -> tr(locale, "NETWORK CONFIG", "SÍŤOVÁ KONFIGURACE", "СЕТЕВОЙ КОНФИГ");
            case CUSTOM_COMMANDS -> tr(locale, "CUSTOM COMMANDS", "VLASTNÍ PŘÍKAZY", "СВОИ КОМАНДЫ");
            case HOLOGRAMS -> tr(locale, "HOLOGRAMS", "HOLOGRAMY", "ГОЛОГРАММЫ");
            case MODERATION -> tr(locale, "MODERATION", "MODERACE", "МОДЕРАЦИЯ");
        };
    }

    private static String countText(String locale, int count) {
        if ("cs".equals(locale)) {
            if (count == 1) return "1 změna";
            if (count >= 2 && count <= 4) return count + " změny";
            return count + " změn";
        }
        if ("ru".equals(locale)) {
            int mod10 = count % 10;
            int mod100 = count % 100;
            if (mod10 == 1 && mod100 != 11) return count + " изменение";
            if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return count + " изменения";
            return count + " изменений";
        }
        return count == 1 ? "1 change" : count + " changes";
    }

    private static String moreText(String locale, int count) {
        if ("cs".equals(locale)) return "a dalších " + count;
        if ("ru".equals(locale)) return "и ещё " + count;
        return "and " + count + " more";
    }

    private static String tr(String locale, String english, String czech, String russian) {
        if ("cs".equals(locale)) return czech;
        if ("ru".equals(locale)) return russian;
        return english;
    }

    private static String safe(String value) {
        return value != null ? value.trim() : "";
    }
}
