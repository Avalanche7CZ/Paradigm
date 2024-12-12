package eu.avalanche7.forgeannouncements.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.forgeannouncements.data.CustomCommand;
import eu.avalanche7.forgeannouncements.utils.DebugLogger;
import net.minecraftforge.fml.common.Mod;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = "forgeannouncements", bus = Mod.EventBusSubscriber.Bus.MOD)
public class CMConfig {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Path CONFIG_FOLDER_PATH = Path.of("config", "forgeannouncements", "commands");
    private static List<CustomCommand> loadedCommands = new ArrayList<>();

    public static void loadCommands() {
        loadedCommands.clear();

        try {
            Files.createDirectories(CONFIG_FOLDER_PATH);
            Files.list(CONFIG_FOLDER_PATH)
                    .filter(path -> path.toString().endsWith(".json"))
                    .forEach(file -> {
                        try (FileReader reader = new FileReader(file.toFile())) {
                            CustomCommand[] commands = GSON.fromJson(reader, CustomCommand[].class);
                            if (commands != null) {
                                for (CustomCommand command : commands) {
                                    loadedCommands.add(command);
                                }
                            }
                            DebugLogger.debugLog("Loaded commands from file: " + file.getFileName());
                        } catch (IOException e) {
                            DebugLogger.debugLog("Failed to load commands from file: " + file.getFileName(), e);
                        }
                    });

            if (loadedCommands.isEmpty()) {
                DebugLogger.debugLog("No valid commands found in the configuration folder. Generating example commands.");
                generateDefaultConfig();
            }

        } catch (IOException e) {
            DebugLogger.debugLog("Failed to read commands configuration folder.", e);
        }
    }

    public static void saveCommands() {
        try {
            Files.createDirectories(CONFIG_FOLDER_PATH);
            Path exampleFile = CONFIG_FOLDER_PATH.resolve("example.json");

            try (FileWriter writer = new FileWriter(exampleFile.toFile())) {
                GSON.toJson(loadedCommands, writer);
                DebugLogger.debugLog("Commands configuration saved successfully to example.json.");
            }
        } catch (IOException e) {
            DebugLogger.debugLog("Failed to save commands configuration.", e);
        }
    }

    private static void generateDefaultConfig() {
        List<CustomCommand> defaultCommands = new ArrayList<>();

        List<CustomCommand.Action> actions1 = new ArrayList<>();
        List<String> welcomeText = new ArrayList<>();
        welcomeText.add("&aHello &6[player]! &bWelcome to the server!");
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

        loadedCommands = defaultCommands;
        saveCommands();
    }

    public static List<CustomCommand> getLoadedCommands() {
        if (loadedCommands.isEmpty()) {
            loadCommands();
        }
        return loadedCommands;
    }

    public static void reloadCommands() {
        loadCommands();
    }
}
