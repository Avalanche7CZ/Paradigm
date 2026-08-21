package eu.avalanche7.paradigm.configs;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;

public final class TicketsConfigHandler extends BaseConfigHandler<TicketsConfigHandler.Config> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static TicketsConfigHandler instance;
    private Config config;

    private TicketsConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "tickets.json");
    }

    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (instance == null) {
            synchronized (TicketsConfigHandler.class) {
                if (instance == null) {
                    instance = new TicketsConfigHandler(platformConfig);
                    instance.setJsonValidator(debugLogger);
                    instance.config = instance.load();
                }
            }
        }
    }

    public static boolean isInitialized() {
        return instance != null && instance.config != null;
    }

    public static Config getConfig() {
        if (instance == null) throw new IllegalStateException("TicketsConfigHandler is not initialized.");
        return instance.config;
    }

    public static Config configOrNull() {
        return instance != null ? instance.config : null;
    }

    public static void reload() {
        if (instance != null) instance.config = instance.load();
    }

    public static void persistConfig() {
        if (instance != null && instance.config != null) instance.save(instance.config);
    }

    @Override
    protected Config createDefaultConfig() {
        return new Config();
    }

    @Override
    protected Class<Config> getConfigClass() {
        return Config.class;
    }

    public static final class Config {
        public ConfigEntry<Boolean> enabled = new ConfigEntry<>(
                true,
                "Enable or disable the ticket system, its /ticket and /tickets commands and the dashboard section."
        );
        public ConfigEntry<Boolean> staffNotifyEnabled = new ConfigEntry<>(
                true,
                "Notify online staff in chat when a ticket is created or a player replies."
        );
        public ConfigEntry<Boolean> creatorNotifyEnabled = new ConfigEntry<>(
                true,
                "Notify the ticket creator in chat when staff reply or the ticket is resolved, closed or reopened."
        );
        public ConfigEntry<Integer> maxOpenTicketsPerPlayer = new ConfigEntry<>(
                3,
                "Maximum number of tickets a player may have in a non-resolved, non-closed state. Set to 0 for unlimited."
        );
        public ConfigEntry<Integer> createCooldownSeconds = new ConfigEntry<>(
                120,
                "Minimum seconds between ticket creations by the same player. Measured from their most recent ticket "
                        + "regardless of its status, so closing a ticket does not bypass the cooldown. Set to 0 to disable."
        );
        public ConfigEntry<Integer> maxMessageLength = new ConfigEntry<>(
                512,
                "Maximum length of a single ticket message. Longer messages are rejected with a localized error."
        );
        public ConfigEntry<Integer> maxSubjectLength = new ConfigEntry<>(
                64,
                "Maximum length of the subject derived from the first line of a new ticket."
        );
        public ConfigEntry<Boolean> allowPlayerUrgentPriority = new ConfigEntry<>(
                false,
                "Allow players without the paradigm.ticket.priority.urgent permission to set URGENT priority."
        );
        public ConfigEntry<Integer> playerReopenWindowHours = new ConfigEntry<>(
                48,
                "How many hours after a ticket is resolved the creator may reopen it themselves. "
                        + "Closed tickets always require staff. Set to 0 to never allow player reopen."
        );
        public ConfigEntry<Integer> autoCloseResolvedAfterHours = new ConfigEntry<>(
                0,
                "Automatically close RESOLVED tickets after this many hours without activity. 0 disables auto-close."
        );
        public ConfigEntry<Integer> autoCloseWaitingPlayerAfterDays = new ConfigEntry<>(
                0,
                "Automatically close WAITING_PLAYER tickets after this many days without activity. 0 disables auto-close."
        );
        public ConfigEntry<Integer> autoCloseSweepIntervalMinutes = new ConfigEntry<>(
                30,
                "How often the auto-close sweeper runs. Only scheduled when at least one auto-close threshold is above 0."
        );
        public ConfigEntry<Integer> crossServerNotifyPollSeconds = new ConfigEntry<>(
                5,
                "How often this server polls the shared ticket event table so staff here are notified about tickets and "
                        + "replies that originated on another server. Requires a shared SQL provider; on JSON storage "
                        + "notifications are local to this server only. Set to 0 to disable polling."
        );
        public ConfigEntry<Integer> listPageSize = new ConfigEntry<>(
                8,
                "How many tickets are shown per page by /ticket list and /tickets."
        );
        public ConfigEntry<Integer> threadPreviewMessages = new ConfigEntry<>(
                5,
                "How many of the most recent messages /ticket view shows inline."
        );
        public List<CategoryEntry> categories = defaultCategories();

        public CategoryEntry category(String id) {
            if (id == null || categories == null) {
                return null;
            }
            for (CategoryEntry entry : categories) {
                if (entry != null && entry.id != null && entry.id.equalsIgnoreCase(id.trim())) {
                    return entry;
                }
            }
            return null;
        }
    }

    public static final class CategoryEntry {
        public String id;
        public String displayName;
        public String description;
        public boolean enabled = true;
        public String defaultPriority = "NORMAL";
        public String permission = "";
        public String staffPermission = "";

        public CategoryEntry() {
        }

        public CategoryEntry(String id, String displayName, String description, String defaultPriority) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.defaultPriority = defaultPriority;
        }
    }

    private static List<CategoryEntry> defaultCategories() {
        List<CategoryEntry> categories = new ArrayList<>();
        categories.add(new CategoryEntry("general", "General", "Anything that does not fit another category.", "NORMAL"));
        categories.add(new CategoryEntry("bug", "Bug Report", "Something on the server is not working as intended.", "NORMAL"));
        categories.add(new CategoryEntry("player_report", "Player Report", "Report another player's behaviour.", "HIGH"));
        categories.add(new CategoryEntry("lost_items", "Lost Items", "Items lost to a crash, bug or rollback.", "NORMAL"));
        categories.add(new CategoryEntry("technical", "Technical", "Connection, performance or account problems.", "NORMAL"));
        categories.add(new CategoryEntry("other", "Other", "Anything else you would like staff to look at.", "LOW"));
        return categories;
    }
}
