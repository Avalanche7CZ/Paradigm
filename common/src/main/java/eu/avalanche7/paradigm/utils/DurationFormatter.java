package eu.avalanche7.paradigm.utils;

public final class DurationFormatter {
    private DurationFormatter() {
    }

    public static String humanize(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        StringBuilder result = new StringBuilder();
        if (days > 0L) {
            result.append(days).append("d ");
        }
        if (days > 0L || hours > 0L) {
            result.append(hours).append("h ");
        }
        if (days > 0L || hours > 0L || minutes > 0L) {
            result.append(minutes).append("m ");
        }
        result.append(seconds).append('s');
        return result.toString();
    }

    public static String compact(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (days > 0L) {
            return days + "d " + hours + "h";
        }
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    public static long wholeHours(long millis) {
        return Math.max(0L, millis) / 3_600_000L;
    }
}
