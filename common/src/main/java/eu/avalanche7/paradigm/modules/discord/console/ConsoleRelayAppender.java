package eu.avalanche7.paradigm.modules.discord.console;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import eu.avalanche7.paradigm.modules.discord.DiscordSanitizer;

public final class ConsoleRelayAppender extends AbstractAppender {
    private static final String OWN_PACKAGE_PREFIX = "eu.avalanche7.paradigm.modules.discord";
    private static final String DISCORD_DEBUG_PREFIX = "[Paradigm-Debug] [Discord]";
    private static final String DISCORD_LOG_PREFIX = "[Paradigm] Discord:";
    private static final int MAX_BUFFERED_LINES = 200;
    private static final int MAX_BUFFERED_CHARS = 8_000;
    private static final int MAX_LINE_LENGTH = 1_200;
    private static final int CHUNK_BODY_BUDGET = 1_800;
    private static final int MAX_STACK_FRAMES = 6;
    private static final String ESC = "\u001b";
    private static final String RESET = ESC + "[0m";
    private static final String DIM = ESC + "[2m";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Object bufferLock = new Object();
    private final Deque<String> buffer = new ArrayDeque<>();
    private final AtomicInteger droppedSinceFlush = new AtomicInteger();
    private int bufferedChars;
    private Level lastLevel;
    private String lastLoggerName;
    private String lastMessage;
    private boolean lastCollapsible;
    private int repeatStreak;
    private long cachedTimeSecond = Long.MIN_VALUE;
    private String cachedTimeText = "";

    private final Level minimumLevel;
    private final List<Pattern> ignoredPatterns;
    private final Runnable onCriticalEvent;

    public ConsoleRelayAppender(String name, Level minimumLevel, List<String> ignoredPatterns) {
        this(name, minimumLevel, ignoredPatterns, null);
    }

    public ConsoleRelayAppender(String name, Level minimumLevel, List<String> ignoredPatterns,
                                Runnable onCriticalEvent) {
        super(name, null, null, true, Property.EMPTY_ARRAY);
        this.minimumLevel = minimumLevel != null ? minimumLevel : Level.INFO;
        this.ignoredPatterns = compile(ignoredPatterns);
        this.onCriticalEvent = onCriticalEvent;
    }

    private static List<Pattern> compile(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        if (patterns == null) {
            return compiled;
        }
        for (String raw : patterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(raw));
            } catch (PatternSyntaxException invalid) {
            }
        }
        return compiled;
    }

    @Override
    public void append(LogEvent event) {
        if (event == null) {
            return;
        }
        String loggerName = event.getLoggerName();
        String message = event.getMessage() != null ? event.getMessage().getFormattedMessage() : "";
        if (isDiscordLog(loggerName, message)) {
            return;
        }
        Level level = event.getLevel();
        if (level == null || !level.isMoreSpecificThan(minimumLevel)) {
            return;
        }
        Throwable thrown = event.getThrown();

        if (thrown == null && collapseRepeat(level, loggerName, message)) {
            return;
        }

        String exceptionSummary = thrown != null ? summarize(thrown) : null;
        String matchable = exceptionSummary != null ? message + " " + exceptionSummary : message;
        for (Pattern pattern : ignoredPatterns) {
            if (pattern.matcher(matchable).find()) {
                return;
            }
        }

        ConsoleSeverity severity = ConsoleSeverity.classify(level, matchable, thrown);
        String rendered = render(level, severity, event.getTimeMillis(), truncate(message));

        synchronized (bufferLock) {
            flushPendingStreakMarkerLocked();
            offerLocked(rendered);
            if (exceptionSummary != null) {
                offerLocked(renderExceptionSummary(truncate(exceptionSummary), severity, level));
                if (severity.warrantsStackFrames()) {
                    for (String frame : renderStackFrames(thrown.getStackTrace(), MAX_STACK_FRAMES)) {
                        offerLocked(frame);
                    }
                }
            }
            rememberLastEntryLocked(level, loggerName, message, thrown == null && severity.isCollapsible());
        }

        if (severity == ConsoleSeverity.CRITICAL && onCriticalEvent != null) {
            try {
                onCriticalEvent.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static boolean isDiscordLog(String loggerName, String message) {
        return loggerName != null && loggerName.startsWith(OWN_PACKAGE_PREFIX)
                || message.startsWith(DISCORD_DEBUG_PREFIX)
                || message.startsWith(DISCORD_LOG_PREFIX);
    }

    private boolean collapseRepeat(Level level, String loggerName, String message) {
        synchronized (bufferLock) {
            if (lastCollapsible && level == lastLevel
                    && Objects.equals(loggerName, lastLoggerName)
                    && message.equals(lastMessage)) {
                repeatStreak++;
                return true;
            }
            return false;
        }
    }

    private void rememberLastEntryLocked(Level level, String loggerName, String message, boolean collapsible) {
        lastLevel = level;
        lastLoggerName = loggerName;
        lastMessage = message;
        lastCollapsible = collapsible;
    }

    private static String summarize(Throwable thrown) {
        return thrown.getClass().getSimpleName() + (thrown.getMessage() != null ? ": " + thrown.getMessage() : "");
    }

    private static String renderExceptionSummary(String summary, ConsoleSeverity severity, Level level) {
        String color = bodyColor(severity, level);
        String base = color.isEmpty() ? DIM : color;
        return base + "    -> " + MinecraftAnsi.translate(summary, base) + RESET;
    }

    private static List<String> renderStackFrames(StackTraceElement[] frames, int limit) {
        List<String> lines = new ArrayList<>();
        int count = Math.min(frames.length, limit);
        for (int i = 0; i < count; i++) {
            lines.add(DIM + "        at " + truncate(frames[i].toString()) + RESET);
        }
        if (frames.length > limit) {
            lines.add(DIM + "        ... " + (frames.length - limit) + " more" + RESET);
        }
        return lines;
    }

    private static String truncate(String text) {
        return DiscordSanitizer.truncate(text, MAX_LINE_LENGTH);
    }

    private String render(Level level, ConsoleSeverity severity, long timeMillis, String message) {
        String time = ESC + "[34m" + formatTime(timeMillis) + RESET;
        String tag = levelTag(level, severity);
        return time + " " + tag + " " + colorizeBody(message, severity, level);
    }

    private String formatTime(long timeMillis) {
        long second = Math.floorDiv(timeMillis, 1000L);
        synchronized (bufferLock) {
            if (second != cachedTimeSecond) {
                cachedTimeSecond = second;
                cachedTimeText = TIME_FORMAT.format(Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()));
            }
            return cachedTimeText;
        }
    }

    private static String colorizeBody(String message, ConsoleSeverity severity, Level level) {
        String base = bodyColor(severity, level);
        return base + MinecraftAnsi.translate(message, base) + RESET;
    }

    private static String bodyColor(ConsoleSeverity severity, Level level) {
        return switch (severity) {
            case CRITICAL -> ESC + "[1;31m";
            case SEVERE -> ESC + "[31m";
            case WARNING -> ESC + "[33m";
            case NOTABLE -> ESC + "[32m";
            case NORMAL -> ConsoleSeverity.isMuted(level) ? DIM : "";
        };
    }

    private static String levelTag(Level level, ConsoleSeverity severity) {
        String color = switch (severity) {
            case CRITICAL -> "1;4;31";
            case SEVERE -> "1;31";
            case WARNING -> "1;33";
            case NOTABLE -> "1;32";
            case NORMAL -> normalTagColor(level);
        };
        String name = level.name();
        String trimmed = name.length() > 5 ? name.substring(0, 5) : name;
        return ESC + "[" + color + "m" + pad("[" + trimmed + "]") + RESET;
    }

    private static String normalTagColor(Level level) {
        if (level == Level.INFO) {
            return "32";
        }
        if (level == Level.DEBUG) {
            return "36";
        }
        if (level == Level.TRACE) {
            return "30";
        }
        return "37";
    }

    private static String pad(String bracketed) {
        int padding = 7 - bracketed.length();
        if (padding <= 0) {
            return bracketed;
        }
        return bracketed + " ".repeat(padding);
    }

    private void offerLocked(String line) {
        buffer.addLast(line);
        bufferedChars += line.length();
        while (!buffer.isEmpty() && (buffer.size() > MAX_BUFFERED_LINES || bufferedChars > MAX_BUFFERED_CHARS)) {
            String removed = buffer.removeFirst();
            bufferedChars -= removed.length();
            droppedSinceFlush.incrementAndGet();
        }
    }

    private void flushPendingStreakMarkerLocked() {
        if (repeatStreak > 0) {
            offerLocked(DIM + "... previous line repeated " + repeatStreak + " more time(s)" + RESET);
            repeatStreak = 0;
        }
    }

    public List<String> drainChunks() {
        List<String> lines;
        int dropped;
        synchronized (bufferLock) {
            flushPendingStreakMarkerLocked();
            lastCollapsible = false;
            lastLevel = null;
            lastLoggerName = null;
            lastMessage = null;
            if (buffer.isEmpty() && droppedSinceFlush.get() == 0) {
                return List.of();
            }
            lines = new ArrayList<>(buffer);
            buffer.clear();
            bufferedChars = 0;
            dropped = droppedSinceFlush.getAndSet(0);
        }

        List<String> withMarker = new ArrayList<>();
        if (dropped > 0) {
            withMarker.add(ESC + "[2;30m... " + dropped + " line(s) truncated" + RESET);
        }
        withMarker.addAll(lines);
        return chunk(withMarker);
    }

    private static List<String> chunk(List<String> lines) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            int extra = current.isEmpty() ? line.length() : line.length() + 1;
            if (current.length() + extra > CHUNK_BODY_BUDGET && !current.isEmpty()) {
                chunks.add(wrap(current.toString()));
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            chunks.add(wrap(current.toString()));
        }
        return chunks;
    }

    private static String wrap(String body) {
        return "```ansi\n" + body + "\n```";
    }
}
