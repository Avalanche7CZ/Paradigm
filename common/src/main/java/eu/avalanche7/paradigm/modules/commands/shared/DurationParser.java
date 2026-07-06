package eu.avalanche7.paradigm.modules.commands.shared;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern TOKEN = Pattern.compile("(\\d+)([smhdw])", Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    public static long parseToMillis(String input) {
        if (input == null || input.isBlank()) {
            return -1L;
        }

        String normalized = input.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = TOKEN.matcher(normalized);
        int consumed = 0;
        long total = 0L;
        while (matcher.find()) {
            if (matcher.start() != consumed) {
                return -1L;
            }
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ex) {
                return -1L;
            }
            long multiplier = switch (matcher.group(2).charAt(0)) {
                case 's' -> Duration.ofSeconds(1).toMillis();
                case 'm' -> Duration.ofMinutes(1).toMillis();
                case 'h' -> Duration.ofHours(1).toMillis();
                case 'd' -> Duration.ofDays(1).toMillis();
                case 'w' -> Duration.ofDays(7).toMillis();
                default -> -1L;
            };
            if (multiplier <= 0L || amount <= 0L) {
                return -1L;
            }
            try {
                total = Math.addExact(total, Math.multiplyExact(amount, multiplier));
            } catch (ArithmeticException ex) {
                return -1L;
            }
            consumed = matcher.end();
        }
        return consumed == normalized.length() && total > 0L ? total : -1L;
    }

    public static String describeRemaining(long expiresAtMs) {
        if (expiresAtMs <= 0L) {
            return "permanent";
        }
        long remaining = Math.max(0L, expiresAtMs - System.currentTimeMillis());
        long seconds = remaining / 1000L;
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        if (days > 0L) return days + "d " + hours + "h";
        if (hours > 0L) return hours + "h " + minutes + "m";
        if (minutes > 0L) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }
}
