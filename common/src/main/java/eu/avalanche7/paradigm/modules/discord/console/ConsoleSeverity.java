package eu.avalanche7.paradigm.modules.discord.console;

import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;

enum ConsoleSeverity {
    NORMAL, NOTABLE, WARNING, SEVERE, CRITICAL;

    private static final Pattern CRASH_PATTERN = Pattern.compile(
            "(?i)(exception in server tick loop|this crash report has been saved to|"
            + "encountered an unexpected exception|preparing crash report|"
            + "server watchdog|considering it to be crashed)");
    private static final String NOTABLE_PREFIX = "Done (";

    static ConsoleSeverity classify(Level level, String matchable, Throwable thrown) {
        if (containsOutOfMemory(thrown) || level == Level.FATAL) {
            return CRITICAL;
        }
        if (!isMuted(level) && CRASH_PATTERN.matcher(matchable).find()) {
            return CRITICAL;
        }
        if (level != null && level.isMoreSpecificThan(Level.ERROR)) {
            return SEVERE;
        }
        if (level == Level.WARN) {
            return WARNING;
        }
        if (level == Level.INFO && matchable.startsWith(NOTABLE_PREFIX)) {
            return NOTABLE;
        }
        return NORMAL;
    }

    static boolean isMuted(Level level) {
        return level == Level.DEBUG || level == Level.TRACE;
    }

    boolean isCollapsible() {
        return this == NORMAL || this == NOTABLE;
    }

    boolean warrantsStackFrames() {
        return ordinal() >= WARNING.ordinal();
    }

    private static boolean containsOutOfMemory(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof OutOfMemoryError) {
                return true;
            }
        }
        return false;
    }
}
