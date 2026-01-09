package eu.avalanche7.paradigm.configs;

import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

public class RestartConfigHandler extends BaseConfigHandler<RestartConfigHandler.Config> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static RestartConfigHandler INSTANCE;
    private Config config;

    private RestartConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "restarts.json");
    }

    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (INSTANCE == null) {
            synchronized (RestartConfigHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new RestartConfigHandler(platformConfig);
                    INSTANCE.setJsonValidator(debugLogger);
                    INSTANCE.config = INSTANCE.load();
                }
            }
        }
    }

    public static Config getConfig() {
        if (INSTANCE == null) {
            throw new IllegalStateException("RestartConfigHandler not initialized! Call init() first.");
        }
        return INSTANCE.config;
    }

    public static void reload() {
        if (INSTANCE != null) {
            INSTANCE.config = INSTANCE.load();
        }
    }

    @Override
    protected Config createDefaultConfig() {
        return new Config();
    }

    @Override
    protected Class<Config> getConfigClass() {
        return Config.class;
    }

    public static class Config {
        public ConfigEntry<String> restartType = new ConfigEntry<>(
                "Realtime",
                "The method for scheduling restarts. Use \"Fixed\" for intervals or \"Realtime\" for specific times of day."
        );

        public ConfigEntry<Double> restartInterval = new ConfigEntry<>(
                6.0,
                "If restartType is \"Fixed\", this is the interval in hours between restarts."
        );

        public ConfigEntry<List<String>> realTimeInterval = new ConfigEntry<>(
                Arrays.asList("00:00", "06:00", "12:00", "18:00"),
                "If restartType is \"Realtime\", this is a list of times (in HH:mm format) to restart the server."
        );

        public ConfigEntry<Boolean> bossbarEnabled = new ConfigEntry<>(
                true,
                "Enable or disable the boss bar warning for restarts."
        );

        public ConfigEntry<String> bossBarMessage = new ConfigEntry<>(
                "&cThe server will be restarting in {minutes}:{seconds}",
                "The message to display in the boss bar. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );

        public ConfigEntry<Boolean> timerUseChat = new ConfigEntry<>(
                true,
                "Enable or disable sending restart warnings to the chat."
        );

        public ConfigEntry<String> BroadcastMessage = new ConfigEntry<>(
                "&cThe server will be restarting in &e{time}",
                "The message to broadcast in chat. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );

        public ConfigEntry<List<Integer>> timerBroadcast = new ConfigEntry<>(
                Arrays.asList(3600, 1800, 600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1),
                "A list of times in seconds before a restart to broadcast a warning."
        );

        public ConfigEntry<String> defaultRestartReason = new ConfigEntry<>(
                "&6The server is restarting!",
                "The default kick message shown to players when the server restarts."
        );

        public ConfigEntry<Boolean> playSoundEnabled = new ConfigEntry<>(
                true,
                "Enable or disable playing a sound effect for restart warnings."
        );

        public ConfigEntry<Double> playSoundFirstTime = new ConfigEntry<>(
                60.0,
                "The time in seconds before a restart at which to start playing warning sounds."
        );

        public ConfigEntry<Boolean> titleEnabled = new ConfigEntry<>(
                true,
                "Enable or disable the on-screen title warning for restarts."
        );

        public ConfigEntry<Integer> titleStayTime = new ConfigEntry<>(
                2,
                "How long the title warning should stay on screen, in seconds."
        );

        public ConfigEntry<String> titleMessage = new ConfigEntry<>(
                "&cRestarting in {minutes}:{seconds}",
                "The message to display as a title. Placeholders: {hours}, {minutes}, {seconds}, {time}."
        );

        public ConfigEntry<List<PreRestartCommand>> preRestartCommands = new ConfigEntry<>(
                Arrays.asList(
                        new PreRestartCommand(30, "broadcast &e[Paradigm] Restarting in 30 seconds..."),
                        new PreRestartCommand(10, "[asPlayer] tell {player_name} &cServer restarting in {seconds}s")
                ),
                "Commands to run seconds before the restart. Each item has 'secondsBefore' and 'command'. Use [asPlayer], asplayer:, or each: at the start to run the command once per online player as that player (with per-player placeholders). Without a marker, the command runs as console."
        );
    }

    public static class PreRestartCommand {
        public int secondsBefore;
        public String command;

        public PreRestartCommand() {
            this.secondsBefore = 5;
            this.command = "broadcast &e[Paradigm] Restarting soon...";
        }

        public PreRestartCommand(int secondsBefore, String command) {
            this.secondsBefore = secondsBefore;
            this.command = command;
        }
    }
}