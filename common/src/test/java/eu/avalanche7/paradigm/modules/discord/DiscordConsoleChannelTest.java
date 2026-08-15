package eu.avalanche7.paradigm.modules.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.discord.client.DiscordInboundMessage;
import eu.avalanche7.paradigm.modules.discord.console.ConsoleRelayAppender;
import eu.avalanche7.paradigm.utils.Placeholders;

class DiscordConsoleChannelTest {
    @TempDir
    Path tempDir;

    private DiscordConfigHandler.Config config;
    private DiscordTestSupport.RecordingOutbox outbox;
    private DiscordTestSupport.RecordingPlatform platform;
    private DiscordRelay relay;

    @BeforeEach
    void setUp() {
        config = DiscordTestSupport.enabledConfig(tempDir);
        config.consoleChannelId.value = "555000111222333444";
        outbox = new DiscordTestSupport.RecordingOutbox(config);
        platform = new DiscordTestSupport.RecordingPlatform(new Placeholders());
        Services services = DiscordTestSupport.services(platform.adapter, new Placeholders());
        relay = new DiscordRelay(services, outbox);
    }

    @Test
    void consoleDestinationNeverFallsBackToNotifications() {
        assertTrue(DiscordDestination.CONSOLE.channelId(config).equals("555000111222333444"));
        config.consoleChannelId.value = "";
        assertEquals("", DiscordDestination.CONSOLE.channelId(config),
                "an unset console channel must never fall back to another channel");
    }

    @Test
    void commandRunsForAnyHumanAuthorInTheChannel() {
        config.allowConsoleCommands.value = true;
        relay.handleConsoleCommand(command("say hi"));

        assertEquals(List.of("say hi"), platform.executedConsoleCommands);
        assertEquals(1, outbox.auditedConsoleCommands.size());
    }

    @Test
    void toggleOffDeniesEvenInTheChannel() {
        config.allowConsoleCommands.value = false;
        relay.handleConsoleCommand(command("say hi"));

        assertTrue(platform.executedConsoleCommands.isEmpty());
    }

    @Test
    void botAuthorsAreAlwaysDenied() {
        config.allowConsoleCommands.value = true;
        DiscordInboundMessage message = new DiscordInboundMessage("1", config.consoleChannelId.get(),
                config.guildId.get(), "999", "SomeBot", true, false, false, "say hi", List.of(), Map.of());
        relay.handleConsoleCommand(message);

        assertTrue(platform.executedConsoleCommands.isEmpty(), "bot authors must never trigger a console command");
    }

    @Test
    void webhookAuthorsAreAlwaysDenied() {
        config.allowConsoleCommands.value = true;
        DiscordInboundMessage message = new DiscordInboundMessage("1", config.consoleChannelId.get(),
                config.guildId.get(), "999", "AWebhook", false, true, false, "say hi", List.of(), Map.of());
        relay.handleConsoleCommand(message);

        assertTrue(platform.executedConsoleCommands.isEmpty(), "webhook authors must never trigger a console command");
    }

    @Test
    void leadingSlashIsStripped() {
        config.allowConsoleCommands.value = true;
        relay.handleConsoleCommand(command("/say hi"));

        assertEquals(List.of("say hi"), platform.executedConsoleCommands);
    }

    @Test
    void blankContentIsANoOp() {
        config.allowConsoleCommands.value = true;
        relay.handleConsoleCommand(command("   "));

        assertTrue(platform.executedConsoleCommands.isEmpty());
    }

    @Test
    void authorizeConsoleCommandMatchesTheChannelBeforeAnythingElse() {
        config.allowConsoleCommands.value = true;
        assertTrue(relay.authorizeConsoleCommand(config.consoleChannelId.get(), false, false, false));
        assertFalse(relay.authorizeConsoleCommand("some-other-channel", false, false, false),
                "a message/interaction from any other channel must never be authorized");
    }

    @Test
    void authorizeConsoleCommandGivesTheSameVerdictRegardlessOfEntryPoint() {
        config.allowConsoleCommands.value = true;
        String channel = config.consoleChannelId.get();

        boolean fromPlainMessage = relay.authorizeConsoleCommand(channel, false, false, false);
        boolean fromSlashCommand = relay.authorizeConsoleCommand(channel, false, false, false);
        assertEquals(fromPlainMessage, fromSlashCommand);
        assertTrue(fromPlainMessage);

        config.allowConsoleCommands.value = false;
        assertEquals(relay.authorizeConsoleCommand(channel, false, false, false),
                relay.authorizeConsoleCommand(channel, false, false, false));
        assertFalse(relay.authorizeConsoleCommand(channel, false, false, false));
    }

    @Test
    void ownsConsoleChannelOnlyMatchesTheConfiguredChannel() {
        assertTrue(relay.ownsConsoleChannel(config.consoleChannelId.get()));
        assertFalse(relay.ownsConsoleChannel("999888777666555444"),
                "an interaction from another instance's console channel is not ours to answer");
    }

    @Test
    void ownsConsoleChannelIsFalseWhenNoConsoleChannelIsConfigured() {
        config.consoleChannelId.value = "";
        assertFalse(relay.ownsConsoleChannel(""),
                "an unset console channel must never match, or a blank interaction channel would look owned");
        assertFalse(relay.ownsConsoleChannel("555000111222333444"));
    }

    @Test
    void ownsConsoleChannelIsIndependentOfTheAllowConsoleCommandsToggle() {
        config.allowConsoleCommands.value = false;
        assertTrue(relay.ownsConsoleChannel(config.consoleChannelId.get()),
                "ownership decides whether to answer at all; the toggle only decides whether to permit the command");
        assertFalse(relay.authorizeConsoleCommand(config.consoleChannelId.get(), false, false, false));
    }

    @Test
    void dispatchConsoleCommandCanBeDrivenDirectlyLikeTheSlashCommandPathDoes() {
        relay.dispatchConsoleCommand("42", "Admin", "/gamemode creative");

        assertEquals(List.of("gamemode creative"), platform.executedConsoleCommands);
        assertEquals(1, outbox.auditedConsoleCommands.size());
    }

    private DiscordInboundMessage command(String content) {
        return new DiscordInboundMessage("1", config.consoleChannelId.get(), config.guildId.get(), "42", "Admin",
                false, false, false, content, List.of(), Map.of());
    }

    // --- ConsoleRelayAppender ---

    @Test
    void appenderDropsLinesBelowTheMinimumLevel() {
        ConsoleRelayAppender appender = new ConsoleRelayAppender("test", Level.WARN, List.of());
        appender.append(event(Level.INFO, "eu.example.Foo", "hidden"));
        appender.append(event(Level.ERROR, "eu.example.Foo", "shown"));

        List<String> chunks = appender.drainChunks();
        assertEquals(1, chunks.size());
        assertFalse(chunks.get(0).contains("hidden"));
        assertTrue(chunks.get(0).contains("shown"));
    }

    @Test
    void appenderNeverRelaysItsOwnPackageLogs() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.ERROR, "eu.avalanche7.paradigm.modules.discord.DiscordDispatcher", "boom"));
        appender.append(event(Level.INFO, "eu.avalanche7.paradigm.modules.discord.console.ConsoleRelayAppender", "boom2"));

        assertTrue(appender.drainChunks().isEmpty(),
                "logs from our own Discord package must never be relayed, to prevent a feedback loop");
    }

    @Test
    void appenderNeverRelaysDiscordLogsWrittenThroughSharedLoggers() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.INFO, "eu.avalanche7.paradigm",
                "[Paradigm-Debug] [Discord] Console relay flush failed: boom"));
        appender.append(event(Level.WARN, "eu.avalanche7.paradigm.Paradigm",
                "[Paradigm] Discord: the bot cannot manage webhooks"));

        assertTrue(appender.drainChunks().isEmpty(),
                "Discord logs using shared Paradigm loggers must not feed back into the Discord console relay");
    }

    @Test
    void appenderHonorsIgnorePatterns() {
        ConsoleRelayAppender appender = new ConsoleRelayAppender("test", Level.INFO, List.of("^noisy.*"));
        appender.append(event(Level.INFO, "eu.example.Foo", "noisy spam"));
        appender.append(event(Level.INFO, "eu.example.Foo", "keep this"));

        List<String> chunks = appender.drainChunks();
        assertEquals(1, chunks.size());
        assertFalse(chunks.get(0).contains("noisy spam"));
        assertTrue(chunks.get(0).contains("keep this"));
    }

    @Test
    void everyChunkIsWrappedInASingleAnsiFence() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.ERROR, "eu.example.Foo", "boom"));

        String chunk = appender.drainChunks().get(0);
        assertTrue(chunk.startsWith("```ansi\n"));
        assertTrue(chunk.endsWith("\n```"));
        assertEquals(1, chunk.split("```ansi").length - 1, "exactly one ansi fence opener per chunk");
    }

    @Test
    void backlogOverflowKeepsTheNewestLinesAndAddsATruncationMarker() {
        ConsoleRelayAppender appender = appender();
        for (int i = 0; i < 260; i++) {
            appender.append(event(Level.INFO, "eu.example.Foo", "line-" + i));
        }

        String combined = String.join("\n", appender.drainChunks());
        assertTrue(combined.contains("truncated"));
        assertTrue(combined.contains("line-259"), "the newest line must survive the cap");
        assertFalse(combined.contains("line-0 "), "the oldest lines must be dropped first");
    }

    @Test
    void emptyBufferProducesNoChunks() {
        ConsoleRelayAppender appender = appender();
        assertTrue(appender.drainChunks().isEmpty());
    }

    @Test
    void thrownExceptionsRenderASummaryLineThenBoundedStackFrames() {
        ConsoleRelayAppender appender = appender();
        IllegalStateException thrown = new IllegalStateException("bad state");
        thrown.setStackTrace(syntheticFrames(9));
        appender.append(Log4jLogEvent.newBuilder()
                .setLevel(Level.ERROR)
                .setLoggerName("eu.example.Foo")
                .setMessage(new SimpleMessage("something broke"))
                .setThrown(thrown)
                .build());

        List<String> chunks = appender.drainChunks();
        assertEquals(1, chunks.size());
        String[] lines = chunks.get(0).split("\n");
        assertEquals(11, lines.length, "fence, message, summary, 6 bounded frames, an overflow marker, fence");
        assertTrue(lines[1].contains("something broke"));
        assertFalse(lines[1].contains("IllegalStateException"), "the exception summary must not be inline with the message");
        assertTrue(lines[2].contains("IllegalStateException: bad state"));
        for (int i = 0; i < 6; i++) {
            assertTrue(lines[3 + i].contains("at com.example.Class" + i + ".method" + i), "frame " + i + " must be present and in order");
        }
        assertTrue(lines[9].contains("... 3 more"));
    }

    @Test
    void stackFramesAreDimmedAndIndentedBelowTheExceptionSummary() {
        ConsoleRelayAppender appender = appender();
        IllegalStateException thrown = new IllegalStateException("bad state");
        thrown.setStackTrace(syntheticFrames(1));
        appender.append(Log4jLogEvent.newBuilder()
                .setLevel(Level.ERROR)
                .setLoggerName("eu.example.Foo")
                .setMessage(new SimpleMessage("boom"))
                .setThrown(thrown)
                .build());

        String[] lines = appender.drainChunks().get(0).split("\n");
        assertEquals(5, lines.length, "fence, message, summary, one frame, fence");
        assertTrue(lines[3].contains("\u001b[2m        at "), "stack frames render dim and indented");
        assertTrue(lines[3].contains("com.example.Class0.method0"));
    }

    @Test
    void warnLevelExceptionsAlsoGetStackFrames() {
        ConsoleRelayAppender appender = appender();
        IllegalStateException thrown = new IllegalStateException("careful");
        thrown.setStackTrace(syntheticFrames(1));
        appender.append(Log4jLogEvent.newBuilder()
                .setLevel(Level.WARN)
                .setLoggerName("eu.example.Foo")
                .setMessage(new SimpleMessage("warned"))
                .setThrown(thrown)
                .build());

        String chunk = appender.drainChunks().get(0);
        assertTrue(chunk.contains("at com.example.Class0.method0"), "WARN-level throwables must also get stack frames");
    }

    @Test
    void infoLevelExceptionsStayAsASummaryLineOnlyNoFrames() {
        ConsoleRelayAppender appender = appender();
        IllegalStateException thrown = new IllegalStateException("fyi");
        thrown.setStackTrace(syntheticFrames(1));
        appender.append(Log4jLogEvent.newBuilder()
                .setLevel(Level.INFO)
                .setLoggerName("eu.example.Foo")
                .setMessage(new SimpleMessage("info with throwable"))
                .setThrown(thrown)
                .build());

        List<String> chunks = appender.drainChunks();
        String[] lines = chunks.get(0).split("\n");
        assertEquals(4, lines.length, "fence, message, summary, fence -- no frames below WARNING severity");
        assertFalse(chunks.get(0).contains("at com.example.Class0"));
    }

    private static StackTraceElement[] syntheticFrames(int count) {
        StackTraceElement[] frames = new StackTraceElement[count];
        for (int i = 0; i < count; i++) {
            frames[i] = new StackTraceElement("com.example.Class" + i, "method" + i, "Class" + i + ".java", i);
        }
        return frames;
    }

    // --- ANSI severity coloring ---

    @Test
    void warnLinesGetAColoredBodyNotJustTheTag() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.WARN, "eu.example.Foo", "low disk space"));

        String chunk = appender.drainChunks().get(0);
        assertTrue(chunk.contains("\u001b[33mlow disk space"), "the WARN body itself must be colored, not just the tag");
    }

    @Test
    void errorLinesGetAColoredBodyNotJustTheTag() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.ERROR, "eu.example.Foo", "disk write failed"));

        String chunk = appender.drainChunks().get(0);
        assertTrue(chunk.contains("\u001b[31mdisk write failed"), "the ERROR body itself must be colored, not just the tag");
    }

    @Test
    void criticalCrashPatternLinesGetBoldUnderlineStyling() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.ERROR, "eu.example.Foo", "Exception in server tick loop"));

        String chunk = appender.drainChunks().get(0);
        assertTrue(chunk.contains("\u001b[1;4;31m"), "a crash-pattern line must use the reserved bold+underline+red styling");
    }

    // --- Minecraft colour codes ---

    @Test
    void infoLinesKeepAuthorColoursTranslatedIntoAnsi() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.INFO, "eu.example.Foo", "\u00a7b[Storage] \u00a7fData location: ./config"));

        String chunk = appender.drainChunks().get(0);
        assertFalse(chunk.contains("\u00a7"), "raw section signs must never reach the Discord code block");
        assertTrue(chunk.contains("\u001b[1;36m[Storage] "), "the mod's aqua should survive as ansi");
        assertTrue(chunk.contains("\u001b[1;37mData location: ./config"));
    }

    @Test
    void debugLinesKeepAuthorColoursToo() {
        ConsoleRelayAppender appender = new ConsoleRelayAppender("test", Level.DEBUG, List.of());
        appender.append(event(Level.DEBUG, "eu.example.Foo", "\u00a7b[Storage] \u00a7fnoisy detail"));

        String chunk = appender.drainChunks().get(0);
        assertFalse(chunk.contains("\u00a7"));
        assertTrue(chunk.contains("\u001b[1;36m[Storage] "));
    }

    @Test
    void warnAndErrorLinesKeepAuthorColoursWhileTheTagStillCarriesSeverity() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.WARN, "eu.example.Foo", "\u00a7adisk almost full"));
        appender.append(event(Level.ERROR, "eu.example.Foo", "\u00a7aconnection reset"));

        String chunk = appender.drainChunks().get(0);
        assertTrue(chunk.contains("\u001b[1;32mdisk almost full"), "author colours survive at warn level");
        assertTrue(chunk.contains("\u001b[1;32mconnection reset"), "author colours survive at error level");
        assertTrue(chunk.contains("\u001b[1;33m[WARN"), "the level tag still carries the severity signal");
        assertTrue(chunk.contains("\u001b[1;31m[ERROR"));
    }

    // --- Critical-event bypass callback ---

    @Test
    void criticalEventsInvokeTheOptionalCallbackExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        ConsoleRelayAppender appender = new ConsoleRelayAppender("test", Level.INFO, List.of(), calls::incrementAndGet);
        appender.append(event(Level.FATAL, "eu.example.Foo", "everything is on fire"));

        assertEquals(1, calls.get());
    }

    @Test
    void theCallbackIsNotInvokedForNonCriticalEvents() {
        AtomicInteger calls = new AtomicInteger();
        ConsoleRelayAppender appender = new ConsoleRelayAppender("test", Level.INFO, List.of(), calls::incrementAndGet);
        appender.append(event(Level.WARN, "eu.example.Foo", "just a warning"));
        appender.append(event(Level.ERROR, "eu.example.Foo", "just an error"));
        appender.append(event(Level.INFO, "eu.example.Foo", "just info"));

        assertEquals(0, calls.get());
    }

    @Test
    void theThreeArgConstructorStillWorksWithNoCallback() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.FATAL, "eu.example.Foo", "boom"));

        assertFalse(appender.drainChunks().isEmpty(), "a missing callback must not prevent normal buffering");
    }

    @Test
    void aCallbackThrowingDoesNotPropagateOutOfAppend() {
        ConsoleRelayAppender appender = new ConsoleRelayAppender("test", Level.INFO, List.of(), () -> {
            throw new RuntimeException("callback exploded");
        });

        appender.append(event(Level.FATAL, "eu.example.Foo", "boom"));
        assertFalse(appender.drainChunks().isEmpty(), "append must not throw even if the critical callback does");
    }

    // --- Anti-spam repeat collapsing ---

    @Test
    void identicalConsecutiveInfoLinesAreCollapsedIntoARepeatMarker() {
        ConsoleRelayAppender appender = appender();
        for (int i = 0; i < 5; i++) {
            appender.append(event(Level.INFO, "eu.example.Foo", "same line"));
        }

        String combined = String.join("\n", appender.drainChunks());
        assertEquals(1, combined.split("same line", -1).length - 1, "only the first occurrence should render the actual line");
        assertTrue(combined.contains("repeated 4 more time(s)"));
    }

    @Test
    void nonConsecutiveDuplicatesAreNotCollapsed() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.INFO, "eu.example.Foo", "line-alpha"));
        appender.append(event(Level.INFO, "eu.example.Foo", "line-beta"));
        appender.append(event(Level.INFO, "eu.example.Foo", "line-alpha"));

        String combined = String.join("\n", appender.drainChunks());
        assertFalse(combined.contains("repeated"), "A, B, A is not a consecutive repeat and must not collapse");
        assertEquals(2, combined.split("line-alpha", -1).length - 1, "both non-consecutive occurrences must render individually");
    }

    @Test
    void warnLinesAreNeverCollapsedEvenWhenRepeatedManyTimes() {
        ConsoleRelayAppender appender = appender();
        for (int i = 0; i < 5; i++) {
            appender.append(event(Level.WARN, "eu.example.Foo", "disk almost full"));
        }

        String combined = String.join("\n", appender.drainChunks());
        assertFalse(combined.contains("repeated"), "WARN lines must never be spam-suppressed");
        assertEquals(5, combined.split("disk almost full", -1).length - 1);
    }

    @Test
    void errorLinesAreNeverCollapsedEvenWhenRepeatedManyTimes() {
        ConsoleRelayAppender appender = appender();
        for (int i = 0; i < 5; i++) {
            appender.append(event(Level.ERROR, "eu.example.Foo", "connection reset"));
        }

        String combined = String.join("\n", appender.drainChunks());
        assertFalse(combined.contains("repeated"), "ERROR lines must never be spam-suppressed");
        assertEquals(5, combined.split("connection reset", -1).length - 1);
    }

    @Test
    void drainingMidStreakStillEmitsTheRepeatMarkerForCountsSoFar() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.INFO, "eu.example.Foo", "steady state"));
        appender.append(event(Level.INFO, "eu.example.Foo", "steady state"));
        appender.append(event(Level.INFO, "eu.example.Foo", "steady state"));

        String combined = String.join("\n", appender.drainChunks());
        assertTrue(combined.contains("repeated 2 more time(s)"), "a flush landing mid-streak must not silently drop the count");
    }

    @Test
    void aRepeatStreakThatContinuesAcrossADrainStartsFreshInTheNextBatch() {
        ConsoleRelayAppender appender = appender();
        appender.append(event(Level.INFO, "eu.example.Foo", "steady state"));
        appender.append(event(Level.INFO, "eu.example.Foo", "steady state"));
        appender.drainChunks();

        appender.append(event(Level.INFO, "eu.example.Foo", "steady state"));
        String secondBatch = String.join("\n", appender.drainChunks());

        assertTrue(secondBatch.contains("steady state"),
                "the next batch must show a fresh representative line, not continue an invisible streak");
        assertFalse(secondBatch.contains("repeated"), "a single line in a fresh batch has nothing to collapse yet");
    }

    @Test
    void ignorePatternsStillMatchAgainstTheExceptionSummary() {
        ConsoleRelayAppender appender = new ConsoleRelayAppender("test", Level.INFO, List.of("NoisyException"));
        appender.append(Log4jLogEvent.newBuilder()
                .setLevel(Level.ERROR)
                .setLoggerName("eu.example.Foo")
                .setMessage(new SimpleMessage("boom"))
                .setThrown(new RuntimeException("NoisyException triggered"))
                .build());

        assertTrue(appender.drainChunks().isEmpty(), "an ignore pattern matching the exception text must drop the whole event");
    }

    private static ConsoleRelayAppender appender() {
        return new ConsoleRelayAppender("test", Level.INFO, List.of());
    }

    private static Log4jLogEvent event(Level level, String loggerName, String message) {
        return Log4jLogEvent.newBuilder()
                .setLevel(level)
                .setLoggerName(loggerName)
                .setMessage(new SimpleMessage(message))
                .build();
    }
}
