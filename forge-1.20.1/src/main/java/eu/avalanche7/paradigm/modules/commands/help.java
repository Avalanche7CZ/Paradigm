package eu.avalanche7.paradigm.modules.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.util.HashMap;
import java.util.Map;

public class help implements ParadigmModule {
    private static final String NAME = "Help";
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {}
    @Override
    public void onEnable(Services services) {}
    @Override
    public void onDisable(Services services) {}
    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {}

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        // No event listeners needed for help module
    }

    @Override
    public void registerCommands(CommandDispatcher<?> dispatcher, Services services) {
        CommandDispatcher<CommandSourceStack> dispatcherCS = (CommandDispatcher<CommandSourceStack>) dispatcher;
        Map<String, String> moduleHelps = new HashMap<>();
        moduleHelps.put("GroupChat", "GroupChat: Create private groups and chat with your friends!\nCommands: /groupchat");
        moduleHelps.put("StaffChat", "StaffChat: A private chat for staff members.\nCommands: /sc toggle, /sc <message>\nOnly staff can see these messages!");
        moduleHelps.put("Mentions", "Mentions: Highlight your name in chat and get notified when someone mentions you!\nNo commands needed, just chat naturally.");
        moduleHelps.put("Announcements", "Announcements: Broadcast important messages to all players!\nCommands: /paradigm broadcast... <message>\nAdmins only.");
        moduleHelps.put("MOTD", "MOTD: Show a message of the day when players join!\nConfigurable in the config file.");
        moduleHelps.put("Restart", "Restart: Schedule (in config) or trigger server restarts with notifications.\nCommands: /restart now");
        moduleHelps.put("CustomCommands", "CustomCommands: Define your own commands in the config!\nNo code needed, just edit the config file.");
        moduleHelps.put("JoinLeaveMessages", "JoinLeaveMessages: Custom join/leave messages for your server!\nConfigurable in the config file.");

        SuggestionProvider<CommandSourceStack> moduleSuggestions = (ctx, builder) ->
            SharedSuggestionProvider.suggest(
                java.util.List.of(
                    "GroupChat", "StaffChat", "Mentions", "Announcements", "MOTD", "Restart", "CustomCommands", "JoinLeaveMessages", "Help", "Reload"
                ), builder
            );

        dispatcherCS.register(Commands.literal("paradigm")
            .then(Commands.literal("help")
                .executes(ctx -> {
                    ICommandSource source = services.getPlatformAdapter().wrapCommandSource(ctx.getSource());
                    IPlayer player = source.getPlayer();
                    IComponent header = services.getPlatformAdapter().createLiteralComponent("§b§l[ Paradigm Help ]");
                    IComponent listHeader = services.getPlatformAdapter().createLiteralComponent("§7Available Modules:");
                    services.getPlatformAdapter().sendSystemMessage(player, header);
                    services.getPlatformAdapter().sendSystemMessage(player, listHeader);
                    for (ParadigmModule module : Paradigm.getModulesStatic()) {
                        String modName = module.getName();
                        IComponent modLine = services.getPlatformAdapter().createLiteralComponent("§b- " + modName)
                            .onClickSuggestCommand("/paradigm help " + modName)
                            .onHoverText("Click for help about " + modName);
                        services.getPlatformAdapter().sendSystemMessage(player, modLine);
                    }
                    IComponent hint = services.getPlatformAdapter().createLiteralComponent("§7Click a module or type /paradigm help <module> for details.");
                    services.getPlatformAdapter().sendSystemMessage(player, hint);
                    return 1;
                })
                .then(Commands.argument("module", StringArgumentType.word())
                    .suggests(moduleSuggestions)
                    .executes(ctx -> {
                        ICommandSource source = services.getPlatformAdapter().wrapCommandSource(ctx.getSource());
                        IPlayer player = source.getPlayer();
                        String modName = StringArgumentType.getString(ctx, "module");
                        String helpMsg = moduleHelps.getOrDefault(modName, "No help available for " + modName + ". Try checking the wiki or ask on Discord.");
                        IComponent header = services.getPlatformAdapter().createLiteralComponent("§b§l[ Paradigm Module Help ]");
                        IComponent modTitle = services.getPlatformAdapter().createLiteralComponent("§b" + modName + " Help");
                        IComponent helpText = services.getPlatformAdapter().createLiteralComponent(helpMsg);
                        services.getPlatformAdapter().sendSystemMessage(player, header);
                        services.getPlatformAdapter().sendSystemMessage(player, modTitle);
                        services.getPlatformAdapter().sendSystemMessage(player, helpText);
                        return 1;
                    })
                )
            )
            .executes(ctx -> {
                ICommandSource source = services.getPlatformAdapter().wrapCommandSource(ctx.getSource());
                IPlayer player = source.getPlayer();
                String modId = "paradigm";
                String version = "?";
                try {
                    var modContainerOpt = ModList.get().getModContainerById(modId);
                    if (modContainerOpt.isPresent()) {
                        var modInfo = modContainerOpt.get().getModInfo();
                        version = modInfo.getVersion().toString();
                    }
                } catch (Exception ignored) {}
                IComponent separator = services.getPlatformAdapter().createLiteralComponent("§8§m----------------------------------------");
                services.getPlatformAdapter().sendSystemMessage(player, separator);
                IComponent header = services.getPlatformAdapter().createLiteralComponent("§d§l[ Paradigm Mod v" + version + " ]");
                IComponent author = services.getPlatformAdapter().createLiteralComponent("§6by Avalanche7CZ §d♥");
                IComponent discord = services.getPlatformAdapter().createLiteralComponent("§bDiscord: ")
                    .append(services.getPlatformAdapter().createLiteralComponent("§9https://discord.com/invite/qZDcQdEFqQ")
                        .onClickOpenUrl("https://discord.com/invite/qZDcQdEFqQ")
                        .onHoverText("Click to join the Paradigm Discord!"));
                services.getPlatformAdapter().sendSystemMessage(player, header);
                services.getPlatformAdapter().sendSystemMessage(player, author);
                services.getPlatformAdapter().sendSystemMessage(player, discord);
                services.getPlatformAdapter().sendSystemMessage(player, separator);
                IComponent modulesHeader = services.getPlatformAdapter().createLiteralComponent("§d§lModules:");
                services.getPlatformAdapter().sendSystemMessage(player, modulesHeader);
                for (ParadigmModule module : Paradigm.getModulesStatic()) {
                    boolean enabled = module.isEnabled(services);
                    String color = enabled ? "&a" : "&7";
                    String symbol = enabled ? "✔" : "✖";
                    String nameColor = enabled ? "&f" : "&8";
                    String status = enabled ? "&7(enabled)" : "&8(disabled)";
                    String line = color + symbol + " " + nameColor + module.getName() + " " + status;
                    IComponent modLine = services.getMessageParser().parseMessage(line, player)
                        .onClickSuggestCommand("/paradigm help " + module.getName())
                        .onHoverText("Click for help about " + module.getName());
                    services.getPlatformAdapter().sendSystemMessage(player, modLine);
                }
                services.getPlatformAdapter().sendSystemMessage(player, separator);
                IComponent helpHint = services.getPlatformAdapter().createLiteralComponent("§eType §d/paradigm help <module> §efor details, or click a module above.")
                    .onClickSuggestCommand("/paradigm help ")
                    .onHoverText("Click to start typing a help command");
                services.getPlatformAdapter().sendSystemMessage(player, helpHint);
                IComponent footer = services.getPlatformAdapter().createLiteralComponent("§d§lParadigm - Elevate your server! ✨");
                services.getPlatformAdapter().sendSystemMessage(player, footer);
                services.getPlatformAdapter().sendSystemMessage(player, separator);
                return 1;
            })
        );
    }
}
