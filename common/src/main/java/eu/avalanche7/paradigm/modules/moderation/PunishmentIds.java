package eu.avalanche7.paradigm.modules.moderation;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public final class PunishmentIds {
    private PunishmentIds() {
    }

    public static String create() {
        return "P-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    public static String legacy(String source) {
        UUID id = UUID.nameUUIDFromBytes(("paradigm:punishment:" + String.valueOf(source)).getBytes(StandardCharsets.UTF_8));
        return "P-L-" + id.toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String value) {
        return value != null && value.matches("P-(?:L-)?[A-F0-9]{16,32}");
    }
}
