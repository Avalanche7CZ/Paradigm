package eu.avalanche7.paradigm.modules.permissions;

import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.utils.Lang;

public final class PermissionDisplayFormatter {
    private PermissionDisplayFormatter() {
    }

    public static String context(Lang lang, PermissionContextSet contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return text(lang, "permission.context.global", "global");
        }
        return contexts.canonical();
    }

    public static String expiry(Lang lang, Long expiresAtMs) {
        if (expiresAtMs == null) {
            return text(lang, "permission.expiry.permanent", "permanent");
        }
        if (expiresAtMs <= System.currentTimeMillis()) {
            return text(lang, "permission.expiry.expired", "expired");
        }
        return text(lang, "permission.expiry.expires_in", "expires in {duration}")
                .replace("{duration}", DurationParser.describeRemaining(expiresAtMs));
    }

    private static String text(Lang lang, String key, String fallback) {
        String value = lang != null ? lang.getTranslation(key) : null;
        return value == null || value.equals(key) ? fallback : value;
    }
}
