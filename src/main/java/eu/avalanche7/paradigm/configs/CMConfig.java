package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.utils.DebugLogger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CMConfig {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private File configFolder;
    private List<CustomCommand> loadedCommands = new ArrayList<>();
    private final DebugLogger debugLogger;

    public CMConfig(DebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    public void init(File modConfigDir) {
        this.configFolder = new File(modConfigDir, "commands");
    }

    public void loadCommands() {
        loadedCommands.clear();

        if (!configFolder.exists()) {
            configFolder.mkdirs();
        }

        File[] commandFiles = configFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));

        if (commandFiles == null || commandFiles.length == 0) {
            debugLogger.debugLog("CMConfig: No command files found. Generating example commands.");
            generateDefaultConfigs();
            // Re-list files after generating them
            commandFiles = configFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        }

        if (commandFiles != null) {
            for (File file : commandFiles) {
                try (FileReader reader = new FileReader(file)) {
                    CustomCommand command = gson.fromJson(reader, CustomCommand.class);
                    if (command != null && command.getName() != null && !command.getName().trim().isEmpty()) {
                        this.loadedCommands.add(command);
                        this.debugLogger.debugLog("CMConfig: Loaded command '" + command.getName() + "' from file: " + file.getName());
                    } else {
                        this.debugLogger.debugLog("CMConfig: Skipped a null or invalid command entry in file: " + file.getName());
                    }
                } catch (IOException | com.google.gson.JsonSyntaxException e) {
                    this.debugLogger.debugLog("CMConfig: Failed to load or parse command from file: " + file.getName(), e);
                }
            }
        }
    }

    private void generateDefaultConfigs() {
        saveCommandToFile(createExampleMessage(), "example_message.json");
        saveCommandToFile(createExampleTeleport(), "example_teleport.json");
        saveCommandToFile(createExampleAdmin(), "example_admin_gift.json");
    }

    private void saveCommandToFile(CustomCommand command, String fileName) {
        if (!configFolder.exists()) {
            configFolder.mkdirs();
        }
        File file = new File(configFolder, fileName);
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(command, writer);
        } catch (IOException e) {
            debugLogger.debugLog("CMConfig: Failed to save example command to " + fileName, e);
        }
    }

    private CustomCommand createExampleMessage() {
        CustomCommand cmd = new CustomCommand();
        cmd.setName("example");
        cmd.setDescription("An example custom command.");
        cmd.setRequirePermission(false);
        CustomCommand.Action action = new CustomCommand.Action();
        action.setType("message");
        action.setText(Arrays.asList("&aHello &6{player}! &bWelcome to the server!", "&aEnjoy your stay."));
        cmd.setActions(Collections.singletonList(action));
        return cmd;
    }

    private CustomCommand createExampleTeleport() {
        CustomCommand cmd = new CustomCommand();
        cmd.setName("spawn");
        cmd.setDescription("Teleports the player to spawn.");
        cmd.setRequirePermission(false);
        List<CustomCommand.Action> actions = new ArrayList<>();
        CustomCommand.Action tpAction = new CustomCommand.Action();
        tpAction.setType("teleport");
        tpAction.setX(0.5);
        tpAction.setY(65.0);
        tpAction.setZ(0.5);
        actions.add(tpAction);
        CustomCommand.Action msgAction = new CustomCommand.Action();
        msgAction.setType("message");
        msgAction.setText(Collections.singletonList("&aYou have been teleported to spawn!"));
        actions.add(msgAction);
        cmd.setActions(actions);
        return cmd;
    }

    private CustomCommand createExampleAdmin() {
        CustomCommand cmd = new CustomCommand();
        cmd.setName("admingift");
        cmd.setDescription("Runs multiple commands as the console.");
        cmd.setRequirePermission(true);
        cmd.setPermission("paradigm.command.admingift");
        cmd.setPermissionErrorMessage("&cYou do not have permission to use this command.");
        CustomCommand.Action runCmdAction = new CustomCommand.Action();
        runCmdAction.setType("run_console");
        runCmdAction.setCommands(Arrays.asList("say {player} used the admin gift command!", "give {player} minecraft:diamond 1"));
        cmd.setActions(Collections.singletonList(runCmdAction));
        return cmd;
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
