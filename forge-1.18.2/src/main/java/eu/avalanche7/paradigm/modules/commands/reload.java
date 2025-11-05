package eu.avalanche7.paradigm.modules.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import eu.avalanche7.paradigm.Paradigm;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;

import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;

import java.util.List;
import java.util.Locale;

public class reload implements ParadigmModule {
    private static final String NAME = "Reload";
    private IPlatformAdapter platform;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void registerCommands(CommandDispatcher<?> dispatcher, Services services) {
        this.platform = services.getPlatformAdapter();
        @SuppressWarnings("unchecked")
        CommandDispatcher<CommandSourceStack> dispatcherCS = (CommandDispatcher<CommandSourceStack>) dispatcher;

        SuggestionProvider<CommandSourceStack> configSuggestions = (ctx, builder) -> {
            List<String> options = List.of("main", "announcements", "chat", "motd", "mention", "restart", "customcommands", "all");
            return SharedSuggestionProvider.suggest(options, builder);
        };

        dispatcherCS.register(
            Commands.literal("paradigm")
                .requires(source -> platform.hasCommandPermission(source, "paradigm.command.reload", 2))
                .then(Commands.literal("reload")
                    .then(Commands.argument("config", StringArgumentType.word())
                        .suggests(configSuggestions)
                        .executes(ctx -> {
                            ICommandSource wrappedSource = platform.wrapCommandSource(ctx.getSource());
                            return reloadConfig(wrappedSource, services, platform, StringArgumentType.getString(ctx, "config"));
                        })
                    )
                )
        );
    }

    private int reloadConfig(ICommandSource source, Services services, IPlatformAdapter platform, String configRaw) {
        String config = configRaw.toLowerCase(Locale.ROOT);
        boolean found = true;
        StringBuilder result = new StringBuilder();
        String color = "&a";
        switch (config) {
            case "main":
                MainConfigHandler.load();
                result.append("✔ &aMain config reloaded!");
                break;
            case "announcements":
                AnnouncementsConfigHandler.load();
                Paradigm.getModules().stream()
                    .filter(m -> m instanceof eu.avalanche7.paradigm.modules.Announcements)
                    .findFirst()
                    .ifPresent(m -> ((eu.avalanche7.paradigm.modules.Announcements)m).rescheduleAnnouncements());
                result.append("✔ &aAnnouncements config reloaded!");
                break;
            case "chat":
                ChatConfigHandler.load();
                result.append("✔ &aChat config reloaded!");
                break;
            case "motd":
                MOTDConfigHandler.load();
                result.append("✔ &aMOTD config reloaded!");
                break;
            case "mention":
                MentionConfigHandler.load();
                result.append("✔ &aMention config reloaded!");
                break;
            case "restart":
                RestartConfigHandler.load();
                Paradigm.getModules().stream()
                    .filter(m -> m instanceof eu.avalanche7.paradigm.modules.Restart)
                    .findFirst()
                    .ifPresent(m -> ((eu.avalanche7.paradigm.modules.Restart)m).scheduleNextRestart());
                result.append("✔ &aRestart config reloaded!");
                break;
            case "customcommands":
                services.getCmConfig().reloadCommands();
                services.getPermissionsHandler().refreshCustomCommandPermissions();
                result.append("✔ &aCustom commands reloaded!");
                break;
            case "all":
                MainConfigHandler.load();
                AnnouncementsConfigHandler.load();
                ChatConfigHandler.load();
                MOTDConfigHandler.load();
                MentionConfigHandler.load();
                RestartConfigHandler.load();
                Paradigm.getModules().stream()
                    .filter(m -> m instanceof eu.avalanche7.paradigm.modules.Restart)
                    .findFirst()
                    .ifPresent(m -> ((eu.avalanche7.paradigm.modules.Restart)m).scheduleNextRestart());
                services.getCmConfig().reloadCommands();
                services.getPermissionsHandler().refreshCustomCommandPermissions();
                Paradigm.getModules().stream()
                    .filter(m -> m instanceof eu.avalanche7.paradigm.modules.Announcements)
                    .findFirst()
                    .ifPresent(m -> ((eu.avalanche7.paradigm.modules.Announcements)m).rescheduleAnnouncements());
                result.append("✔ &aAll configs reloaded!");
                break;
            default:
                found = false;
                color = "&c";
                result.append("✖ &cUnknown config: ").append(config).append("! Valid: main, announcements, chat, motd, mention, restart, customcommands, all");
        }
        IComponent message = services.getMessageParser().parseMessage(color + result, null);
        if (found) {
            platform.sendSuccess(source, message, false);
        } else {
            platform.sendFailure(source, message);
        }
        return found ? 1 : 0;
    }

    @Override public void onLoad(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event, Services services, net.minecraftforge.eventbus.api.IEventBus modEventBus) {}
    @Override public void onServerStarting(net.minecraftforge.event.server.ServerStartingEvent event, Services services) {}
    @Override public void onEnable(Services services) {}
    @Override public void onDisable(Services services) {}
    @Override public void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event, Services services) {}
    @Override public void registerEventListeners(net.minecraftforge.eventbus.api.IEventBus forgeEventBus, Services services) {}
}
