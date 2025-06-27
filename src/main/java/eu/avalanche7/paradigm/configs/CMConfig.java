package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.utils.DebugLogger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class CMConfig {

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final Path configFolderPath = Path.of("config", "paradigm", "commands");
    private List<CustomCommand> loadedCommands = new ArrayList<>();
    private final DebugLogger debugLogger;

    public CMConfig(DebugLogger debugLogger) {
        this.debugLogger = debugLogger;
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
                                try (FileReader reader = new FileReader(file.toFile())) {
                                    CustomCommand[] commands = gson.fromJson(reader, CustomCommand[].class);
                                    if (commands != null) {
                                        for (CustomCommand command : commands) {
                                            if (command != null && command.getName() != null && !command.getName().trim().isEmpty()) {
                                                this.loadedCommands.add(command);
                                            } else {
                                                this.debugLogger.debugLog("CMConfig: Skipped a null or invalid command entry in file: " + file.getFileName());
                                            }
                                        }
                                    }
                                    this.debugLogger.debugLog("CMConfig: Loaded commands from file: " + file.getFileName());
                                } catch (IOException | com.google.gson.JsonSyntaxException e) {
                                    this.debugLogger.debugLog("CMConfig: Failed to load or parse commands from file: " + file.getFileName(), e);
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
            Path exampleFile = configFolderPath.resolve("examples.json");

            try (FileWriter writer = new FileWriter(exampleFile.toFile())) {
                gson.toJson(this.loadedCommands, writer);
                this.debugLogger.debugLog("CMConfig: Commands configuration saved successfully to examples.json.");
            }
        } catch (IOException e) {
            this.debugLogger.debugLog("CMConfig: Failed to save commands configuration.", e);
        }
    }

    private void generateDefaultConfig() {
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

        this.loadedCommands = defaultCommands;
        saveCommands();
    }

    public List<CustomCommand> getLoadedCommands() {
        return this.loadedCommands;
    }

    public void reloadCommands() {
        this.debugLogger.debugLog("CMConfig: Reloading custom commands...");
        loadCommands();
        this.debugLogger.debugLog("CMConfig: Custom commands reloaded. Found " + this.loadedCommands.size() + " commands.");
    }
}