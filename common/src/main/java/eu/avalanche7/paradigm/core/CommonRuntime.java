package eu.avalanche7.paradigm.core;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.modules.*;
import eu.avalanche7.paradigm.modules.chat.*;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.*;
import eu.avalanche7.paradigm.webeditor.store.WebEditorStore;
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
        CooldownConfigHandler.init(platformConfig, bootstrapDebugLogger);
        EmojiConfigHandler.init(platformConfig);

        // --- utilities ---
        DebugLogger debugLogger = new DebugLogger(MainConfigHandler.getConfig());
        CMConfig cmConfig = new CMConfig(debugLogger);
        cmConfig.loadCommands();

        Placeholders placeholders = new Placeholders();
        TaskScheduler taskScheduler = new TaskScheduler(debugLogger);

        PermissionsHandler permissionsHandler = new PermissionsHandler(logger, cmConfig, debugLogger, platformAdapter);

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
                platformAdapter,
                new WebEditorStore()
        );

        groupChatManager.setServices(services);


        // --- modules ---
        List<ParadigmModule> modules = new ArrayList<>();
        modules.add(new eu.avalanche7.paradigm.modules.commands.Help());
        modules.add(new Announcements());
        modules.add(new MOTD());
        modules.add(new Mentions());
        modules.add(new Restart());
        modules.add(new StaffChat());
        modules.add(new GroupChat(groupChatManager));
        modules.add(new JoinLeaveMessages());
        modules.add(new CommandManager());
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
}
