package eu.avalanche7.paradigm.core;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.data.AdminUtilityDataStore;
import eu.avalanche7.paradigm.data.ModerationDataStore;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.data.WarpStore;
import eu.avalanche7.paradigm.modules.*;
import eu.avalanche7.paradigm.modules.chat.*;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.utils.*;
import eu.avalanche7.paradigm.modules.webeditor.store.WebEditorStore;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CommonRuntime {
    private CommonRuntime() {}

    public static Runtime bootstrap(Logger logger, IConfig platformConfig, IPlatformAdapter platformAdapter) {
        if (logger == null) {
            throw new IllegalArgumentException("logger cannot be null");
        }
        if (platformConfig == null) {
            throw new IllegalArgumentException("platformConfig cannot be null");
        }
        if (platformAdapter == null) {
            throw new IllegalArgumentException("platformAdapter cannot be null");
        }

        // --- configs ---
        DebugLogger bootstrapDebugLogger = new DebugLogger(null);
        MainConfigHandler.init(platformConfig, bootstrapDebugLogger);
        bootstrapDebugLogger = new DebugLogger(MainConfigHandler.getConfig());

        AnnouncementsConfigHandler.init(platformConfig, bootstrapDebugLogger);
        MOTDConfigHandler.init(platformConfig, bootstrapDebugLogger);
        MentionConfigHandler.init(platformConfig, bootstrapDebugLogger);
        RestartConfigHandler.init(platformConfig, bootstrapDebugLogger);
        ChatConfigHandler.init(platformConfig, bootstrapDebugLogger);
        ModerationConfigHandler.init(platformConfig, bootstrapDebugLogger);
        CooldownConfigHandler.init(platformConfig, bootstrapDebugLogger);
        EmojiConfigHandler.init(platformConfig, bootstrapDebugLogger);

        // --- utilities ---
        DebugLogger debugLogger = new DebugLogger(MainConfigHandler.getConfig());
        CMConfig cmConfig = new CMConfig(debugLogger, platformConfig);
        cmConfig.loadCommands();

        Placeholders placeholders = new Placeholders();
        TaskScheduler taskScheduler = new TaskScheduler(debugLogger);
        PlayerDataStore playerDataStore = new PlayerDataStore(logger, debugLogger, platformAdapter.getConfig());
        ModerationDataStore moderationDataStore = new ModerationDataStore(logger, debugLogger, platformAdapter.getConfig());
        AdminUtilityDataStore adminUtilityDataStore = new AdminUtilityDataStore(logger, platformAdapter.getConfig());
        WarpStore warpStore = new WarpStore(logger, debugLogger, platformAdapter.getConfig());
        StorageService storageService = new StorageService(logger, debugLogger, platformAdapter.getConfig(), playerDataStore, warpStore, moderationDataStore, adminUtilityDataStore);
        CommandToggleStore commandToggleStore = new CommandToggleStore(logger, debugLogger, platformAdapter.getConfig());

        PermissionsHandler permissionsHandler = new PermissionsHandler(logger, cmConfig, debugLogger, platformAdapter, playerDataStore, storageService);

        MessageParser messageParser = new MessageParser(placeholders, platformAdapter);
        platformAdapter.provideMessageParser(messageParser);

        Lang lang = new Lang(logger, MainConfigHandler.getConfig(), messageParser, platformAdapter);
        lang.initializeLanguage();

        GroupChatManager groupChatManager = new GroupChatManager();

        Services services = new Services(
                logger,
                MainConfigHandler.getConfig(),
                AnnouncementsConfigHandler.getConfig(),
                MOTDConfigHandler.getConfig(),
                MentionConfigHandler.getConfig(),
                RestartConfigHandler.getConfig(),
                ChatConfigHandler.getConfig(),
                cmConfig,
                groupChatManager,
                debugLogger,
                lang,
                messageParser,
                permissionsHandler,
                placeholders,
                taskScheduler,
                playerDataStore,
                moderationDataStore,
                adminUtilityDataStore,
                storageService,
                commandToggleStore,
                platformAdapter,
                new WebEditorStore()
        );

        services.getPunishmentService();

        registerDefaultCommandToggles(commandToggleStore);

        UpdateChecker.registerInGameNotifier(services);

        groupChatManager.setServices(services);
        registerExternalCommandGuard(services);
        registerPunishmentGuard(services);


        // --- modules ---
        List<ParadigmModule> modules = new ArrayList<>();
        modules.add(new StorageLifecycle());
        modules.add(new eu.avalanche7.paradigm.modules.commands.Help());
        modules.add(new Announcements());
        modules.add(new MOTD());
        modules.add(new Mentions());
        modules.add(new Restart());
        modules.add(new StaffChat());
        modules.add(new PrivateMessages());
        modules.add(new GroupChat(groupChatManager));
        modules.add(new JoinLeaveMessages());
        modules.add(new CommandManager());
        modules.add(new eu.avalanche7.paradigm.modules.commands.HomeCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.TpaCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.WarpCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.SpawnCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.SeenCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.IgnoreCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.GamemodeCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.FlyCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.ClearInventoryCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.TimeWeatherCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.SpeedCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.FeedCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.HealCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.moderation.KickCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.moderation.BanCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.moderation.TempBanCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.moderation.IpBanCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.moderation.MuteCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.moderation.TempMuteCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.moderation.WarnCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.moderation.JailCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.admin.VanishCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.admin.GodCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.admin.InventoryInspectCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.admin.RepairCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.admin.EnchantCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.admin.SudoCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.admin.NearCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.admin.WhoisCommand());
        modules.add(new eu.avalanche7.paradigm.modules.commands.admin.MovementUtilityCommand());
        modules.add(new eu.avalanche7.paradigm.modules.dashboard.LocalDashboardModule());
        modules.add(new eu.avalanche7.paradigm.modules.commands.Reload());
        modules.add(new eu.avalanche7.paradigm.modules.commands.editor());

        return new Runtime(Collections.unmodifiableList(modules), services, permissionsHandler);
    }

    public static void attachToApi(Runtime runtime, String modVersion) {
        if (runtime == null) throw new IllegalArgumentException("runtime cannot be null");
        ParadigmAPI.setInstance(new ParadigmAPI.ParadigmAccessor() {
            @Override
            public List<ParadigmModule> getModules() {
                return runtime.modules();
            }

            @Override
            public Services getServices() {
                return runtime.services();
            }

            @Override
            public String getModVersion() {
                return modVersion != null ? modVersion : "unknown";
            }
        });
    }

    public record Runtime(List<ParadigmModule> modules, Services services, PermissionsHandler permissionsHandler) {
    }

    private static void registerExternalCommandGuard(Services services) {
        if (services == null || services.getPlatformAdapter() == null || services.getPlatformAdapter().getEventSystem() == null) {
            return;
        }
        services.getPlatformAdapter().getEventSystem().onPlayerCommand(event -> {
            if (event == null || event.isCancelled()) {
                return;
            }
            IPlatformAdapter platform = services.getPlatformAdapter();
            PermissionsHandler.CommandGuardResult result = services.getPermissionsHandler()
                    .evaluateCommandPermission(event.getPlayer(), event.getCommand());
            if (result.allowed()) {
                services.getDebugLogger().debugLog("[Permissions] External command allowed: /" + event.getCommand() + " node=" + result.node() + " reason=" + result.reason());
                return;
            }

            event.setCancelled(true);
            services.getDebugLogger().debugLog("[Permissions] External command denied: /" + event.getCommand() + " node=" + result.node() + " reason=" + result.reason());
            if (event.getPlayer() != null && platform != null) {
                String node = result.node() != null ? result.node() : "unknown";
                platform.sendSystemMessage(
                        event.getPlayer(),
                        platform.createLiteralComponent("§cYou do not have permission to use this command. §7(" + node + ")")
                );
            }
        });
    }

    private static void registerPunishmentGuard(Services services) {
        if (services == null || services.getPlatformAdapter() == null || services.getPlatformAdapter().getEventSystem() == null) return;
        services.getPlatformAdapter().getEventSystem().onPlayerJoin(event -> {
            if (event != null && event.getPlayer() != null) services.getPunishmentService().enforcePlayer(event.getPlayer());
        });
    }

    private static void registerDefaultCommandToggles(CommandToggleStore store) {
        if (store == null) {
            return;
        }

        store.registerCommand("msg", true, false, "tell", "w", "whisper");
        store.registerCommand("reply", true, false, "r");
        store.registerCommand("mention", true, false);
        store.registerCommand("restart", true, false);
        store.registerCommand("customcommands", true, false);

        store.registerCommand("sethome", true, false);
        store.registerCommand("home", true, false);
        store.registerCommand("delhome", true, false);
        store.registerCommand("homes", true, false);
        store.registerCommand("back", true, false);
        store.registerCommand("spawn", true, false);
        store.registerCommand("setspawn", true, false);
        store.registerCommand("seen", true, false);
        store.registerCommand("ignore", true, false);
        store.registerCommand("unignore", true, false);
        store.registerCommand("speed", true, false);
        store.registerCommand("feed", true, false);
        store.registerCommand("heal", true, false);
        store.registerCommand("socialspy", true, false);
        store.registerCommand("gamemode", true, false);
        store.registerCommand("gmc", true, false);
        store.registerCommand("creative", true, false);
        store.registerCommand("gms", true, false);
        store.registerCommand("survival", true, false);
        store.registerCommand("gma", true, false);
        store.registerCommand("adventure", true, false);
        store.registerCommand("gmsp", true, false);
        store.registerCommand("spectator", true, false);
        store.registerCommand("fly", true, false);
        store.registerCommand("clearinv", true, false, "ci");
        store.registerCommand("day", true, false);
        store.registerCommand("night", true, false);
        store.registerCommand("sun", true, false);
        store.registerCommand("rain", true, false);
        store.registerCommand("thunder", true, false);
        store.registerCommand("kick", true, false);
        store.registerCommand("ban", true, false);
        store.registerCommand("unban", true, false);
        store.registerCommand("pardon", true, false);
        store.registerCommand("tempban", true, false);
        store.registerCommand("ipban", true, false);
        store.registerCommand("tempipban", true, false);
        store.registerCommand("unipban", true, false);
        store.registerCommand("mute", true, false);
        store.registerCommand("tempmute", true, false);
        store.registerCommand("unmute", true, false);
        store.registerCommand("warn", true, false);
        store.registerCommand("setjail", true, false);
        store.registerCommand("jail", true, false);
        store.registerCommand("unjail", true, false);
        store.registerCommand("vanish", true, false);
        store.registerCommand("god", true, false);
        store.registerCommand("invsee", true, false);
        store.registerCommand("endersee", true, false);
        store.registerCommand("repair", true, false);
        store.registerCommand("enchant", true, false);
        store.registerCommand("sudo", true, false);
        store.registerCommand("near", true, false);
        store.registerCommand("whois", true, false);
        store.registerCommand("top", true, false);
        store.registerCommand("jump", true, false);

        store.registerCommand("tpa", true, false);
        store.registerCommand("tpahere", true, false);
        store.registerCommand("tpaccept", true, false);
        store.registerCommand("tpdeny", true, false);
        store.registerCommand("tpcancel", true, false);

        store.registerCommand("warp", true, false);
        store.registerCommand("warps", true, false);
        store.registerCommand("setwarp", true, false);
        store.registerCommand("delwarp", true, false);
        store.registerCommand("warpinfo", true, false);

        store.registerCommand("paradigm.editor", true, false, "editor");
        store.registerCommand("paradigm.apply", true, false, "apply");
        store.registerCommand("paradigm.help", true, false, "help");
        store.registerCommand("paradigm.command", true, true, "command", "commands");
        store.registerCommand("paradigm.dashboard", true, true, "dashboard");
    }
}
