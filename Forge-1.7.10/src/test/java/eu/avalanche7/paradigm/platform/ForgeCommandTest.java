package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder.ArgumentType;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class ForgeCommandTest {
    private final PlatformAdapterImpl platform = mock(PlatformAdapterImpl.class);
    private final ICommandSender sender = mock(ICommandSender.class);

    @Test
    void rootNestedLiteralAndBranchPriority() {
        List<String> executed = new ArrayList<>();
        ForgeCommandBuilder root = builder().literal("sample").executes(ctx -> {
            executed.add("root");
            return 1;
        }).then(builder().argument("value", ArgumentType.WORD).executes(ctx -> {
            executed.add("word:" + ctx.getStringArgument("value"));
            return 1;
        })).then(builder().literal("status").executes(ctx -> {
            executed.add("literal");
            return 1;
        }).then(builder().literal("detail").executes(ctx -> {
            executed.add("nested");
            return 1;
        })));
        ForgeCommand command = new ForgeCommand(root, platform);

        command.processCommand(sender, new String[0]);
        command.processCommand(sender, new String[] {"other"});
        command.processCommand(sender, new String[] {"status"});
        command.processCommand(sender, new String[] {"status", "detail"});

        assertEquals(List.of("root", "word:other", "literal", "nested"), executed);
    }

    @Test
    void stringsGreedyIntegersAndBooleansParseOrFailCleanly() {
        AtomicReference<ICommandContext> seen = new AtomicReference<>();
        ForgeCommandBuilder root = builder().literal("sample")
                .then(builder().literal("string").then(builder().argument("text", ArgumentType.STRING)
                        .executes(ctx -> capture(seen, ctx))))
                .then(builder().literal("greedy").then(builder().argument("text", ArgumentType.GREEDY_STRING)
                        .executes(ctx -> capture(seen, ctx))))
                .then(builder().literal("integer").then(builder().argument("number", ArgumentType.INTEGER)
                        .executes(ctx -> capture(seen, ctx))))
                .then(builder().literal("boolean").then(builder().argument("flag", ArgumentType.BOOLEAN)
                        .executes(ctx -> capture(seen, ctx))));
        ForgeCommand command = new ForgeCommand(root, platform);

        command.processCommand(sender, new String[] {"string", "one"});
        assertEquals("one", seen.get().getStringArgument("text"));
        command.processCommand(sender, new String[] {"greedy", "one", "two", "three"});
        assertEquals("one two three", seen.get().getStringArgument("text"));
        command.processCommand(sender, new String[] {"integer", "-12"});
        assertEquals(-12, seen.get().getIntArgument("number"));
        command.processCommand(sender, new String[] {"boolean", "TRUE"});
        assertTrue(seen.get().getBooleanArgument("flag"));
        command.processCommand(sender, new String[] {"boolean", "false"});
        assertFalse(seen.get().getBooleanArgument("flag"));

        IComponent error = mock(IComponent.class);
        when(platform.createLiteralComponent(any())).thenReturn(error);
        command.processCommand(sender, new String[] {"integer", "NaN"});
        command.processCommand(sender, new String[] {"boolean", "maybe"});
        command.processCommand(sender, new String[] {"integer"});
        verify(platform, org.mockito.Mockito.times(3)).sendFailure(any(), any());
    }

    @Test
    void playerLookupAndAbsentContextValues() {
        IPlayer player = mock(IPlayer.class);
        when(platform.getPlayerByName("Alice")).thenReturn(player);
        AtomicReference<ICommandContext> seen = new AtomicReference<>();
        ForgeCommand command = new ForgeCommand(builder().literal("sample")
                .then(builder().argument("target", ArgumentType.PLAYER)
                        .executes(ctx -> capture(seen, ctx))), platform);

        command.processCommand(sender, new String[] {"Alice"});
        assertEquals(player, seen.get().getPlayerArgument("target"));
        assertEquals("", seen.get().getStringArgument("missing"));
        assertEquals(0, seen.get().getIntArgument("missing"));
        assertFalse(seen.get().getBooleanArgument("missing"));
        assertNull(seen.get().getPlayerArgument("missing"));
        command.processCommand(sender, new String[] {"Missing"});
        verify(platform).sendFailure(any(), any());
    }

    @Test
    void requirementsUseNativeSourceAndDenyExecution() {
        List<String> executed = new ArrayList<>();
        ForgeCommand command = new ForgeCommand(builder().literal("sample")
                .requires(source -> source.isConsole() || source.hasPermissionLevel(2))
                .executes(ctx -> {
                    executed.add(ctx.getSource().getSourceName());
                    return 1;
                }), platform);
        when(sender.canCommandSenderUseCommand(2, "paradigm")).thenReturn(false);
        assertFalse(command.canCommandSenderUseCommand(sender));
        command.processCommand(sender, new String[0]);
        assertTrue(executed.isEmpty());
        when(sender.canCommandSenderUseCommand(2, "paradigm")).thenReturn(true);
        when(sender.getCommandSenderName()).thenReturn("operator");
        assertTrue(command.canCommandSenderUseCommand(sender));
        command.processCommand(sender, new String[0]);
        assertEquals(List.of("operator"), executed);
    }

    @Test
    void consoleAndPlayerSourcesRemainDistinct() {
        MinecraftServer console = mock(MinecraftServer.class);
        EntityPlayerMP player = mock(EntityPlayerMP.class);
        when(console.getCommandSenderName()).thenReturn("Server");
        when(player.getCommandSenderName()).thenReturn("Alice");
        MinecraftCommandSource consoleSource = new MinecraftCommandSource(console);
        MinecraftCommandSource playerSource = new MinecraftCommandSource(player);

        assertTrue(consoleSource.isConsole());
        assertNull(consoleSource.getPlayer());
        assertEquals("Server", consoleSource.getSourceName());
        assertFalse(playerSource.isConsole());
        assertEquals("Alice", playerSource.getSourceName());
        assertEquals(player, playerSource.getPlayer().getOriginalPlayer());
    }

    @Test
    void nativeExecutionHelpersStripOptionalSlashAndKeepSender() {
        DebugLogger logger = new DebugLogger(null);
        PlatformAdapterImpl actual = new PlatformAdapterImpl(null, new Placeholders(),
                new TaskScheduler(logger), logger, new ForgeConfig(Path.of("test-config")));
        MinecraftServer console = mock(MinecraftServer.class);
        CommandHandler manager = mock(CommandHandler.class);
        EntityPlayerMP player = mock(EntityPlayerMP.class);
        when(console.getCommandManager()).thenReturn(manager);
        actual.setMinecraftServer(console);

        actual.executeCommandAsConsole("/paradigm help");
        actual.executeCommandAs(new MinecraftCommandSource(player), "paradigm help");

        verify(manager).executeCommand(console, "paradigm help");
        verify(manager).executeCommand(player, "paradigm help");
        assertEquals(console, actual.getConsoleCommandSource());
        assertEquals(player, actual.createCommandSourceForPlayer(new MinecraftPlayer(player))
                .getOriginalSource());
    }

    @Test
    void literalsPlayersBooleansAndProvidersSuggestValidValues() {
        when(platform.getOnlinePlayerNames()).thenReturn(List.of("Alice", "Bob"));
        ForgeCommand command = new ForgeCommand(builder().literal("sample")
                .then(builder().literal("status"))
                .then(builder().literal("target").then(builder().argument("player", ArgumentType.PLAYER)))
                .then(builder().literal("flag").then(builder().argument("value", ArgumentType.BOOLEAN)))
                .then(builder().literal("choice").then(builder().argument("value", ArgumentType.WORD)
                        .suggests((ctx, input) -> List.of("alpha", "beta"))))
                .then(builder().literal("phrase").then(builder().argument("value", ArgumentType.GREEDY_STRING)
                        .suggests((ctx, input) -> List.of("hello world", "good night")))), platform);

        assertEquals(List.of("status"), command.addTabCompletionOptions(sender, new String[] {"st"}));
        assertEquals(List.of("Alice"), command.addTabCompletionOptions(sender, new String[] {"target", "Al"}));
        assertEquals(List.of("true"), command.addTabCompletionOptions(sender, new String[] {"flag", "tr"}));
        assertEquals(List.of("beta"), command.addTabCompletionOptions(sender, new String[] {"choice", "be"}));
        assertEquals(List.of("hello world"),
                command.addTabCompletionOptions(sender, new String[] {"phrase", "hello", "wo"}));
    }

    @Test
    void occupiedExternalRootIsNotReplacedAndOwnRootCanGrow() {
        DebugLogger logger = new DebugLogger(null);
        PlatformAdapterImpl actual = new PlatformAdapterImpl(null, new Placeholders(),
                new TaskScheduler(logger), logger, new ForgeConfig(Path.of("test-config")));
        MinecraftServer server = mock(MinecraftServer.class);
        CommandHandler manager = mock(CommandHandler.class);
        Map<String, ICommand> commands = new HashMap<>();
        ICommand external = mock(ICommand.class);
        commands.put("external", external);
        when(server.getCommandManager()).thenReturn(manager);
        when(manager.getCommands()).thenReturn(commands);
        when(manager.registerCommand(any())).thenAnswer(invocation -> {
            ICommand command = invocation.getArgument(0);
            commands.put(command.getCommandName(), command);
            return command;
        });
        actual.setMinecraftServer(server);

        actual.registerCommand(builder().literal("external"));
        actual.registerCommand(builder().literal("external"));
        assertEquals(external, commands.get("external"));

        actual.registerCommand(builder().literal("sample"));
        ICommand own = commands.get("sample");
        actual.registerCommand(builder().literal("sample"));
        assertEquals(own, commands.get("sample"));
        verify(manager, org.mockito.Mockito.times(1)).registerCommand(any());
    }

    @Test
    void sharedRootKeepsFirstDuplicatePathAndRefreshesContributors() {
        DebugLogger logger = new DebugLogger(null);
        PlatformAdapterImpl actual = new PlatformAdapterImpl(null, new Placeholders(),
                new TaskScheduler(logger), logger, new ForgeConfig(Path.of("test-config")));
        MinecraftServer server = mock(MinecraftServer.class);
        CommandHandler manager = new CommandHandler();
        when(server.getCommandManager()).thenReturn(manager);
        actual.setMinecraftServer(server);
        List<String> executed = new ArrayList<>();
        AtomicBoolean firstEnabled = new AtomicBoolean(true);
        Object first = new Object();
        Object second = new Object();

        actual.registerCommandContributor(first, () -> {
            if (firstEnabled.get()) {
                actual.registerCommand(builder().literal("paradigm")
                        .then(builder().literal("help").executes(ctx -> {
                            executed.add("first");
                            return 1;
                        })));
            }
        });
        assertTrue(manager.getCommands().get("paradigm") instanceof ForgeCommand);
        actual.registerCommandContributor(second, () -> actual.registerCommand(builder().literal("paradigm")
                .then(builder().literal("help").executes(ctx -> {
                    executed.add("second duplicate");
                    return 1;
                }))
                .then(builder().literal("status").executes(ctx -> {
                    executed.add("second");
                    return 1;
                }))));
        ForgeCommand command = (ForgeCommand) manager.getCommands().get("paradigm");
        command.processCommand(sender, new String[] {"help"});
        command.processCommand(sender, new String[] {"status"});
        assertEquals(List.of("first", "second"), executed);
        assertEquals(List.of("help", "status"),
                command.addTabCompletionOptions(sender, new String[] {""}));

        firstEnabled.set(false);
        assertTrue(actual.refreshRegisteredCommandContributors());
        command = (ForgeCommand) manager.getCommands().get("paradigm");
        command.processCommand(sender, new String[] {"help"});
        assertEquals(List.of("first", "second", "second duplicate"), executed);
        firstEnabled.set(true);
        assertTrue(actual.refreshRegisteredCommandContributors());
        command = (ForgeCommand) manager.getCommands().get("paradigm");
        command.processCommand(sender, new String[] {"help"});
        assertEquals("first", executed.get(executed.size() - 1));
    }

    @Test
    void unregisterReleasesOnlyParadigmRootsAndCanRegisterAgain() {
        DebugLogger logger = new DebugLogger(null);
        PlatformAdapterImpl actual = new PlatformAdapterImpl(null, new Placeholders(),
                new TaskScheduler(logger), logger, new ForgeConfig(Path.of("test-config")));
        MinecraftServer server = mock(MinecraftServer.class);
        CommandHandler manager = new CommandHandler();
        when(server.getCommandManager()).thenReturn(manager);
        actual.setMinecraftServer(server);
        ICommand external = mock(ICommand.class);
        manager.getCommands().put("external", external);

        actual.registerCommand(builder().literal("external"));
        assertEquals(external, manager.getCommands().get("external"));
        assertFalse(actual.ownsRegisteredCommandRoot("external"));
        assertFalse(actual.unregisterCommandRoot("external"));
        actual.registerCommand(builder().literal("sample"));
        assertTrue(actual.ownsRegisteredCommandRoot("sample"));
        assertTrue(actual.unregisterCommandRoot("sample"));
        assertFalse(manager.getCommands().containsKey("sample"));
        assertFalse(actual.ownsRegisteredCommandRoot("sample"));
        assertFalse(actual.unregisterCommandRoot("sample"));
        actual.registerCommand(builder().literal("sample"));
        assertTrue(manager.getCommands().get("sample") instanceof ForgeCommand);
        ICommand replacement = mock(ICommand.class);
        manager.getCommands().put("sample", replacement);
        assertTrue(actual.unregisterCommandRoot("sample"));
        assertEquals(replacement, manager.getCommands().get("sample"));
    }

    @Test
    void deniedSharedBranchReportsPermissionInsteadOfUnknownInput() {
        IComponent message = mock(IComponent.class);
        when(platform.createLiteralComponent(any())).thenReturn(message);
        ForgeCommand command = new ForgeCommand(builder().literal("paradigm")
                .then(builder().literal("help")), platform);
        command.addRoot(builder().literal("paradigm")
                .then(builder().literal("command").requires(source -> false)
                        .then(builder().literal("status").executes(ctx -> 1))));

        command.processCommand(sender, new String[] {"command", "status"});
        verify(platform).createLiteralComponent("§cYou do not have permission to use this command.");
        command.processCommand(sender, new String[] {"nonsense"});
        verify(platform).createLiteralComponent("§cInvalid or incomplete command: paradigm nonsense");
    }

    @Test
    void toggledContributorReleasesAndRestoresNativeRootAndSuggestions() {
        DebugLogger logger = new DebugLogger(null);
        PlatformAdapterImpl actual = new PlatformAdapterImpl(null, new Placeholders(),
                new TaskScheduler(logger), logger, new ForgeConfig(Path.of("test-config")));
        MinecraftServer server = mock(MinecraftServer.class);
        CommandHandler manager = new CommandHandler();
        when(server.getCommandManager()).thenReturn(manager);
        actual.setMinecraftServer(server);
        AtomicBoolean enabled = new AtomicBoolean(true);
        actual.registerCommandContributor("toggle", () -> {
            if (enabled.get()) {
                actual.registerCommand(builder().literal("sample")
                        .then(builder().literal("status").executes(ctx -> 1)));
            }
        });
        assertEquals(List.of("status"), ((ForgeCommand) manager.getCommands().get("sample"))
                .addTabCompletionOptions(sender, new String[] {"st"}));

        enabled.set(false);
        assertTrue(actual.refreshRegisteredCommandContributors());
        assertFalse(manager.getCommands().containsKey("sample"));
        assertFalse(manager.getPossibleCommands(sender).stream()
                .anyMatch(command -> ((ICommand) command).getCommandName().equals("sample")));

        enabled.set(true);
        assertTrue(actual.refreshRegisteredCommandContributors());
        assertEquals(List.of("status"), ((ForgeCommand) manager.getCommands().get("sample"))
                .addTabCompletionOptions(sender, new String[] {"st"}));

        actual.registerCommandContributor("toggle", () -> actual.registerCommand(builder().literal("sample")
                .then(builder().literal("details").executes(ctx -> 1))));
        ForgeCommand replaced = (ForgeCommand) manager.getCommands().get("sample");
        assertEquals(List.of(), replaced.addTabCompletionOptions(sender, new String[] {"st"}));
        assertEquals(List.of("details"), replaced.addTabCompletionOptions(sender, new String[] {"de"}));
    }

    private static int capture(AtomicReference<ICommandContext> seen, ICommandContext context) {
        seen.set(context);
        return 1;
    }

    private static ForgeCommandBuilder builder() {
        return new ForgeCommandBuilder();
    }
}
