package eu.avalanche7.paradigm.modules.tickets;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TicketIds {

    private static final Pattern KEY_PATTERN = Pattern.compile("T-\\d{1,18}");
    private static final Pattern RECORD_PATTERN = Pattern.compile("(?:TK|TM|TE)-[A-F0-9]{32}");

    private TicketIds() {
    }

    public static String key(long number) {
        return "T-" + Math.max(1L, number);
    }

    public static boolean isValidKey(String value) {
        return value != null && KEY_PATTERN.matcher(value.trim().toUpperCase(Locale.ROOT)).matches();
    }

    public static String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.chars().allMatch(Character::isDigit)) {
            trimmed = "T-" + trimmed;
        }
        return isValidKey(trimmed) ? trimmed : null;
    }

    public static String ticketId() {
        return "TK-" + randomHex();
    }

    public static String messageId() {
        return "TM-" + randomHex();
    }

    public static String eventId() {
        return "TE-" + randomHex();
    }

    public static boolean isValidRecordId(String value) {
        return value != null && RECORD_PATTERN.matcher(value.trim().toUpperCase(Locale.ROOT)).matches();
    }

    private static String randomHex() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }
}
