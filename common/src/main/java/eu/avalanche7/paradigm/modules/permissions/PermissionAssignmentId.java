package eu.avalanche7.paradigm.modules.permissions;

import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable IDs for permission assignments, including legacy entries that predate IDs. */
public final class PermissionAssignmentId {
    private PermissionAssignmentId() {
    }

    public static String generated() {
        return UUID.randomUUID().toString();
    }

    public static String deterministic(String kind, String subject, String value, boolean denied,
                                       PermissionContextSet contexts, Long expiresAtMs, String source) {
        String canonical = (kind != null ? kind : "") + "\u0000"
                + (subject != null ? subject : "") + "\u0000"
                + (value != null ? value : "") + "\u0000"
                + denied + "\u0000"
                + (contexts != null ? contexts.canonical() : "") + "\u0000"
                + (expiresAtMs != null ? expiresAtMs : "") + "\u0000"
                + (source != null ? source : "");
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String ensure(String id, String kind, String subject, String value, boolean denied,
                                PermissionContextSet contexts, Long expiresAtMs, String source) {
        return id != null && !id.isBlank() ? id.trim() : deterministic(kind, subject, value, denied, contexts, expiresAtMs, source);
    }
}
