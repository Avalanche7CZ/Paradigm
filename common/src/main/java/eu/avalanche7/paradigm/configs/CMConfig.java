package eu.avalanche7.paradigm.configs;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.modules.actions.PlayerInputService;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.AtomicFileIO;
import eu.avalanche7.paradigm.utils.DebugLogger;

public class CMConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(CMConfig.class);

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final Path configFolderPath;
    private volatile List<CustomCommand> loadedCommands = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Map<Path, List<CustomCommand>> lastGoodFiles = Map.of();

    public record LoadResult(Map<Path, List<CustomCommand>> files, List<String> errors) {}
    private final DebugLogger debugLogger;

    public CMConfig(DebugLogger debugLogger) {
        this(debugLogger, null);
    }

    public CMConfig(DebugLogger debugLogger, IConfig platformConfig) {
        this.debugLogger = debugLogger;
        Path configDir;
        try {
            configDir = platformConfig != null
                    ? platformConfig.getConfigDirectory()
                    : Path.of(System.getProperty("user.dir")).resolve("config");
        } catch (Throwable t) {
            configDir = Path.of(System.getProperty("user.dir")).resolve("config");
        }
        this.configFolderPath = configDir.resolve("paradigm").resolve("commands");
    }

    public void loadCommands() {
        try {
            var result = readCommands();
            publishCommands(result);
            result.errors().forEach(error -> LOGGER.warn("CMConfig: {}", error));
        } catch (IllegalArgumentException failure) {
            LOGGER.warn("CMConfig: {}", failure.getMessage());
        }
    }

    public LoadResult readCommands() {
        Map<Path, List<CustomCommand>> candidate = new java.util.LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        var previous = lastGoodFiles;
        try {
            Files.createDirectories(configFolderPath);
            List<Path> files;
            try (Stream<Path> stream = Files.list(configFolderPath)) {
                files = stream.filter(path -> path.toString().endsWith(".json")).sorted().toList();
            }
            if (files.isEmpty()) {
                var examples = defaultCommands();
                writeExamples(examples);
                candidate.put(configFolderPath.resolve("examples.json"), examples);
            }
            for (Path file : files) {
                String name = file.getFileName().toString();
                List<CustomCommand> lastGood = previous.getOrDefault(file, List.of());
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    CustomCommand[] commands = gson.fromJson(reader, CustomCommand[].class);
                    if (commands == null) throw new IllegalArgumentException("Expected a command list.");
                    var valid = new ArrayList<CustomCommand>();
                    for (CustomCommand command : commands) {
                        if (command == null || command.getName() == null || command.getName().isBlank()) {
                            errors.add(name + ": a custom command has no name.");
                            continue;
                        }
                        try {
                            PlayerInputService.validateActions(command.getActions(), "custom command '" + command.getName() + "'", 0);
                            valid.add(command);
                        } catch (IllegalArgumentException failure) {
                            errors.add(name + ": " + failure.getMessage());
                            lastGood.stream().filter(old -> command.getName().equalsIgnoreCase(old.getName())).findFirst().ifPresent(valid::add);
                        }
                    }
                    candidate.put(file, List.copyOf(valid));
                } catch (IOException | RuntimeException failure) {
                    errors.add(name + ": " + failure.getMessage());
                    candidate.put(file, lastGood);
                }
            }
            return new LoadResult(java.util.Collections.unmodifiableMap(candidate), List.copyOf(errors));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Could not read the custom command directory.", failure);
        }
    }

    public void publishCommands(LoadResult candidate) {
        lastGoodFiles = candidate.files();
        loadedCommands = new java.util.concurrent.CopyOnWriteArrayList<>(
                candidate.files().values().stream().flatMap(List::stream).toList());
    }

    public void saveCommands() {
        try {
            writeExamples(loadedCommands);
            this.debugLogger.debugLog("CMConfig: Commands configuration saved successfully to examples.json.");
        } catch (IOException e) {
            this.debugLogger.debugLog("CMConfig: Failed to save commands configuration.", e);
        }
    }

    private void writeExamples(List<CustomCommand> commands) throws IOException {
        Files.createDirectories(configFolderPath);
        AtomicFileIO.writeUtf8Atomic(configFolderPath.resolve("examples.json"), writer -> gson.toJson(commands, writer));
    }

    private List<CustomCommand> defaultCommands() {
        List<CustomCommand> defaultCommands = new ArrayList<>();

        List<CustomCommand.Action> actions1 = new ArrayList<>();
        actions1.add(new CustomCommand.Action("message", List.of("&aHello &6{player}! &bWelcome to the server!", "&aEnjoy your stay and check out our rules."), null, null, null, null, null, null, null));
        defaultCommands.add(new CustomCommand("example", "Sends a greeting to the player.", "example.custom.permissions", false, null, actions1, null, null, null));

        List<CustomCommand.Action> actions2 = new ArrayList<>();
        actions2.add(new CustomCommand.Action("teleport", null, 100, 64, 100, null, null, null, null));
        actions2.add(new CustomCommand.Action("message", List.of("&aYou have been teleported to spawn!"), null, null, null, null, null, null, null));
        defaultCommands.add(new CustomCommand("example2", "Teleports the player to spawn.", "example2.custom.permissions", false, null, actions2, null, null, null));

        List<CustomCommand.Action> actions3 = new ArrayList<>();
        actions3.add(new CustomCommand.Action("runcmd", null, null, null, null, List.of("say Hello world!", "give @p minecraft:diamond 1"), null, null, null));
        defaultCommands.add(new CustomCommand("example3", "Runs multiple commands for admins.", "example3.custom.permissions", true, null, actions3, null, null, null));

        List<CustomCommand.Action> successActions = new ArrayList<>();
        successActions.add(new CustomCommand.Action("message", List.of("&aSuccess! You had the required item."), null, null, null, null, null, null, null));
        successActions.add(new CustomCommand.Action("run_console", null, null, null, null, List.of("say {player} has at least 5 diamonds!"), null, null, null));

        List<CustomCommand.Action> failureActions = new ArrayList<>();
        failureActions.add(new CustomCommand.Action("message", List.of("&cFailure! You need at least 5 diamonds to use this command."), null, null, null, null, null, null, null));

        List<CustomCommand.Condition> conditions = new ArrayList<>();
        conditions.add(new CustomCommand.Condition("has_item", "minecraft:diamond", 5, false));

        List<CustomCommand.Action> conditionalActionList = new ArrayList<>();
        conditionalActionList.add(new CustomCommand.Action("conditional", null, null, null, null, null, conditions, successActions, failureActions));
        defaultCommands.add(new CustomCommand("checkdiamond", "Checks if you have 5 diamonds.", "paradigm.checkdiamond", false, null, conditionalActionList, null, null, null));

        List<CustomCommand.Action> cooldownActions = new ArrayList<>();
        cooldownActions.add(new CustomCommand.Action("message", List.of("&aYou successfully used the cooldown command!"), null, null, null, null, null, null, null));
        defaultCommands.add(new CustomCommand(
                "cooldown_test",
                "A command with a 30-second cooldown.",
                "paradigm.cooldown.test",
                false,
                null,
                cooldownActions,
                30,
                "&cThis command is on cooldown! Please wait &e{remaining_time} &cseconds.",
                null
        ));

        List<CustomCommand.Action> areaActions = new ArrayList<>();
        areaActions.add(new CustomCommand.Action("message", List.of("&aYou have found the secret altar!"), null, null, null, null, null, null, null));

        CustomCommand.AreaRestriction restriction = new CustomCommand.AreaRestriction(
                "minecraft:overworld",
                Arrays.asList(0, 60, 0),
                Arrays.asList(10, 70, 10),
                "&cYou must be at the secret altar to use this command."
        );

        defaultCommands.add(new CustomCommand(
                "altar_ritual",
                "A command that only works in a specific area.",
                "paradigm.altar.use",
                false,
                null,
                areaActions,
                60,
                "&cThe altar's magic needs time to recharge.",
                restriction
        ));

        return defaultCommands;
    }

    public List<CustomCommand> getLoadedCommands() {
        return this.loadedCommands;
    }

}
