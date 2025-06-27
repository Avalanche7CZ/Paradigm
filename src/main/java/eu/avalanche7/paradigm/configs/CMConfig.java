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
            Path exampleFile = configFolderPath.resolve("example.json");

            try (FileWriter writer = new FileWriter(exampleFile.toFile())) {
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
        List<String> welcomeText = new ArrayList<>();
        welcomeText.add("&aHello &6{player}! &bWelcome to the server!");
        welcomeText.add("&aEnjoy your stay and check out our rules.");
        actions1.add(new CustomCommand.Action("message", welcomeText, null, null, null, null));

        defaultCommands.add(new CustomCommand("example", "Sends a greeting to the player.", "example.custom.permissions", false, actions1));

        List<CustomCommand.Action> actions2 = new ArrayList<>();
        actions2.add(new CustomCommand.Action("teleport", null, 100, 64, 100, null));
        List<String> spawnText = new ArrayList<>();
        spawnText.add("&aYou have been teleported to spawn!");
        actions2.add(new CustomCommand.Action("message", spawnText, null, null, null, null));
        defaultCommands.add(new CustomCommand("example2", "Teleports the player to spawn.", "example2.custom.permissions", false, actions2));

        List<CustomCommand.Action> actions3 = new ArrayList<>();
        List<String> cmds = new ArrayList<>();
        cmds.add("say Hello world!");
        cmds.add("give @p minecraft:diamond 1");
        actions3.add(new CustomCommand.Action("runcmd", null, null, null, null, cmds));

        defaultCommands.add(new CustomCommand("example3", "Runs multiple commands for admins.", "example3.custom.permissions", true, actions3));

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
