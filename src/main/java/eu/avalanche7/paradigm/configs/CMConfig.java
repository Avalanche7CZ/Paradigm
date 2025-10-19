package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.JsonValidator;
import java.util.stream.Stream;

public class CMConfig {

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final Path configFolderPath = Path.of("config", "paradigm", "commands");
    private List<CustomCommand> loadedCommands = new ArrayList<>();
    private final DebugLogger debugLogger;
    private final JsonValidator jsonValidator;

    public CMConfig(DebugLogger debugLogger) {
        this.debugLogger = debugLogger;
        this.jsonValidator = new JsonValidator(debugLogger);
    }

    public void loadCommands() {
        loadedCommands.clear();

        try {
            Files.createDirectories(configFolderPath);

            boolean isEmpty = true;
            if (Files.exists(configFolderPath) && Files.isDirectory(configFolderPath)) {
                try (Stream<Path> stream = Files.list(configFolderPath)) {
                    isEmpty = stream.noneMatch(p -> p.toString().endsWith(".json"));
                }
            }

            if (isEmpty) {
                this.debugLogger.debugLog("CMConfig: No command files found in " + configFolderPath + ". Generating example commands.");
                generateDefaultConfig();
            } else {
                try (Stream<Path> stream = Files.list(configFolderPath)) {
                    stream.filter(path -> path.toString().endsWith(".json"))
                            .forEach(file -> {
                                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                                    StringBuilder content = new StringBuilder();
                                    int c;
                                    while ((c = reader.read()) != -1) {
                                        content.append((char) c);
                                    }

                                    JsonValidator.ValidationResult result = jsonValidator.validateAndFix(content.toString());
                                    if (result.isValid()) {
                                        if (result.hasIssues()) {
                                            this.debugLogger.debugLog("CMConfig: Fixed JSON syntax issues in " + file.getFileName() + ": " + result.getIssuesSummary());
                                            this.debugLogger.debugLog("CMConfig: Using fixed version in memory, original file unchanged");
                                        }

                                        try {
                                            CustomCommand[] commands = gson.fromJson(result.getFixedJson(), CustomCommand[].class);
                                            if (commands != null) {
                                                for (CustomCommand command : commands) {
                                                    if (command != null && command.getName() != null && !command.getName().trim().isEmpty()) {
                                                        this.loadedCommands.add(command);
                                                    } else {
                                                        this.debugLogger.debugLog("CMConfig: Skipped a null or invalid command entry in file: " + file.getFileName());
                                                    }
                                                }
                                                this.debugLogger.debugLog("CMConfig: Loaded commands from file: " + file.getFileName());
                                            } else {
                                                this.debugLogger.debugLog("CMConfig: No commands found in file: " + file.getFileName());
                                            }
                                        } catch (Exception parseError) {
                                            this.debugLogger.debugLog("CMConfig: Failed to parse fixed JSON from " + file.getFileName() + ": " + parseError.getMessage());
                                        }
                                    } else {
                                        this.debugLogger.debugLog("CMConfig: Unable to fix JSON syntax errors in " + file.getFileName() + ": " + result.getMessage());
                                        this.debugLogger.debugLog("CMConfig: Skipping file - please fix syntax manually");
                                    }
                                } catch (IOException e) {
                                    this.debugLogger.debugLog("CMConfig: Failed to read file: " + file.getFileName(), e);
                                }
                            });
                }
                if (this.loadedCommands.isEmpty() && !isEmpty) {
                    this.debugLogger.debugLog("CMConfig: No valid commands loaded from existing files. Consider checking their format or generating defaults.");
                }
            }

        } catch (IOException e) {
            this.debugLogger.debugLog("CMConfig: Failed to read commands configuration folder.", e);
        }
    }

    public void saveCommands() {
        try {
            Files.createDirectories(configFolderPath);
            Path exampleFile = configFolderPath.resolve("example.json");

            try (Writer writer = Files.newBufferedWriter(exampleFile, StandardCharsets.UTF_8)) {
                gson.toJson(this.loadedCommands, writer);
                this.debugLogger.debugLog("CMConfig: Commands configuration saved successfully to example.json.");
            }
        } catch (IOException e) {
            this.debugLogger.debugLog("CMConfig: Failed to save commands configuration.", e);
        }
    }

    private void generateDefaultConfig() {
        List<CustomCommand> defaultCommands = new ArrayList<>();
        List<CustomCommand.Action> actions1 = new ArrayList<>();
        List<String> welcomeText = List.of("&aHello &6{player}! &bWelcome to the server!", "&aEnjoy your stay and check out our rules.");
        actions1.add(new CustomCommand.Action("message", welcomeText, null, null, null, null, null, null, null));
        defaultCommands.add(new CustomCommand("example", "Sends a greeting to the player.", "example.custom.permissions", false, null, actions1));

        List<CustomCommand.Action> actions2 = new ArrayList<>();
        actions2.add(new CustomCommand.Action("teleport", null, 100, 64, 100, null, null, null, null));
        List<String> spawnText = List.of("&aYou have been teleported to spawn!");
        actions2.add(new CustomCommand.Action("message", spawnText, null, null, null, null, null, null, null));
        defaultCommands.add(new CustomCommand("example2", "Teleports the player to spawn.", "example2.custom.permissions", false, null, actions2));

        List<CustomCommand.Action> actions3 = new ArrayList<>();
        List<String> cmds = List.of("say Hello world!", "give @p minecraft:diamond 1");
        actions3.add(new CustomCommand.Action("runcmd", null, null, null, null, cmds, null, null, null));
        defaultCommands.add(new CustomCommand("example3", "Runs multiple commands for admins.", "example3.custom.permissions", true, null, actions3));
        List<CustomCommand.Action> successActions = new ArrayList<>();
        successActions.add(new CustomCommand.Action("message", List.of("&aSuccess! You had the required item."), null, null, null, null, null, null, null));
        successActions.add(new CustomCommand.Action("run_console", null, null, null, null, List.of("say {player} has at least 5 diamonds!"), null, null, null));

        List<CustomCommand.Action> failureActions = new ArrayList<>();
        failureActions.add(new CustomCommand.Action("message", List.of("&cFailure! You need at least 5 diamonds to use this command."), null, null, null, null, null, null, null));

        List<CustomCommand.Condition> conditions = new ArrayList<>();
        conditions.add(new CustomCommand.Condition("has_item", "minecraft:diamond", 5, false));

        List<CustomCommand.Action> conditionalActionList = new ArrayList<>();
        conditionalActionList.add(new CustomCommand.Action("conditional", null, null, null, null, null, conditions, successActions, failureActions));
        defaultCommands.add(new CustomCommand("checkdiamond", "Checks if you have 5 diamonds.", "paradigm.checkdiamond", false, null, conditionalActionList));
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
                "&cThis command is on cooldown! Please wait &e{remaining_time} &cseconds."
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
        List<CustomCommand.Action> teleportActions = new ArrayList<>();
        teleportActions.add(new CustomCommand.Action("message", List.of("&aTeleporting to coordinates $1, $2, $3..."), null, null, null, null, null, null, null));
        teleportActions.add(new CustomCommand.Action("run_console", null, null, null, null, List.of("tp {player} $1 $2 $3"), null, null, null));

        List<CustomCommand.ArgumentDefinition> teleportArgs = new ArrayList<>();
        teleportArgs.add(new CustomCommand.ArgumentDefinition("x", "integer", true, "&cX coordinate must be a valid integer!", null, -30000000, 30000000));
        teleportArgs.add(new CustomCommand.ArgumentDefinition("y", "integer", true, "&cY coordinate must be a valid integer between 0 and 320!", null, 0, 320));
        teleportArgs.add(new CustomCommand.ArgumentDefinition("z", "integer", true, "&cZ coordinate must be a valid integer!", null, -30000000, 30000000));

        defaultCommands.add(new CustomCommand(
                "tpto",
                "Teleports to specific coordinates with validation.",
                "paradigm.tpto",
                false,
                null,
                teleportActions,
                null,
                null,
                null,
                teleportArgs
        ));
        List<CustomCommand.Action> healActions = new ArrayList<>();
        healActions.add(new CustomCommand.Action("message", List.of("&aHealing player $1..."), null, null, null, null, null, null, null));
        healActions.add(new CustomCommand.Action("run_console", null, null, null, null, List.of("effect give $1 minecraft:instant_health 1 10"), null, null, null));

        List<CustomCommand.ArgumentDefinition> healArgs = new ArrayList<>();
        healArgs.add(new CustomCommand.ArgumentDefinition("target", "player", true, "&cYou must specify a valid online player!", null, null, null));

        defaultCommands.add(new CustomCommand(
                "healplayer",
                "Heals a specific player with tab completion.",
                "paradigm.heal",
                false,
                null,
                healActions,
                null,
                null,
                null,
                healArgs
        ));
        List<CustomCommand.Action> weatherActions = new ArrayList<>();
        weatherActions.add(new CustomCommand.Action("conditional", null, null, null, null, null,
                List.of(new CustomCommand.Condition("has_permission", "paradigm.weather.admin", null, false)),
                List.of(new CustomCommand.Action("run_console", null, null, null, null, List.of("weather clear"), null, null, null)),
                List.of(new CustomCommand.Action("message", List.of("&cNo permission."), null, null, null, null, null, null, null))
        ));
        defaultCommands.add(new CustomCommand(
                "weather",
                "Changes the weather if you have permission.",
                "paradigm.weather",
                false,
                null,
                weatherActions
        ));

        this.loadedCommands = defaultCommands;
        saveCommands();
    }

    public List<CustomCommand> getLoadedCommands() {
        return new ArrayList<>(loadedCommands);
    }

    public void reloadCommands() {
        this.debugLogger.debugLog("CMConfig: Reloading custom commands...");
        loadCommands();
        this.debugLogger.debugLog("CMConfig: Custom commands reloaded. Found " + this.loadedCommands.size() + " commands.");
    }
}
