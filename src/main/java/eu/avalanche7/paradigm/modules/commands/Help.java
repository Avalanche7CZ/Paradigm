package eu.avalanche7.paradigm.modules.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.HashMap;

public class Help implements ParadigmModule {
    private static final String NAME = "Help";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("ParadigmHelp");

    @Override
    public String getName() { return NAME; }

    @Override
    public boolean isEnabled(Services services) { return true; }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {}
    @Override
    public void onServerStarting(Object event, Services services) {}
    @Override
    public void onEnable(Services services) {}
    @Override
    public void onDisable(Services services) {}
    @Override
    public void onServerStopping(Object event, Services services) {}
    @Override
    public void registerEventListeners(Object eventBus, Services services) {}

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        Map<String, String> moduleHelps = new HashMap<>();
        moduleHelps.put("GroupChat", "GroupChat: Create private groups and chat with your friends!\nCommands: /groupchat");
        moduleHelps.put("StaffChat", "StaffChat: A private chat for staff members.\nCommands: /sc toggle, /sc <message>\nOnly staff can see these messages!");
        moduleHelps.put("Mentions", "Mentions: @everyone and @name notifications.\nCommands: /mention <message>");
        moduleHelps.put("Announcements", "Announcements: Broadcast messages (chat/actionbar/title/bossbar).\nCommands: /paradigm broadcast|actionbar|title|bossbar");
        moduleHelps.put("MOTD", "MOTD: Show a message of the day when players join.\nConfigurable in the config file.");
        moduleHelps.put("Restart", "Restart: Schedule or trigger restarts with warnings.\nCommands: /restart now|cancel");
        moduleHelps.put("CustomCommands", "CustomCommands: Define your own commands in config.\nCommand: /customcommandsreload");
        moduleHelps.put("Help", "Help: Show this help.\nCommand: /paradigm help [module]");
        moduleHelps.put("Reload", "Reload: Reload configs.\nCommand: /paradigm reload <config>");

        SuggestionProvider<ServerCommandSource> moduleSuggestions = (ctx, builder) -> {
            for (ParadigmModule m : Paradigm.getModules()) builder.suggest(m.getName());
            return builder.buildFuture();
        };

        dispatcher.register(CommandManager.literal("paradigm")
                .executes(ctx -> {
                    String version = Paradigm.getModVersion();
                    LOG.info("[Help] Executing base /paradigm command. Resolved version: {}", version);
                    ServerPlayerEntity player = ctx.getSource().getEntity() instanceof ServerPlayerEntity sp ? sp : null;
                    if (player == null) {
                        ctx.getSource().sendFeedback(() -> Text.literal("Paradigm: This command provides more info in-game."), false);
                        return 1;
                    }

                    String separator = "§8§m----------------------------------------";

                    platform.sendSystemMessage(player, Text.literal(separator));
                    platform.sendSystemMessage(player, Text.literal("§d§l[ Paradigm Mod v" + version + " ]"));
                    platform.sendSystemMessage(player, Text.literal("§6by Avalanche7CZ §d♥"));
                    platform.sendSystemMessage(player, Text.literal("§bDiscord: ")
                            .append(Text.literal("§9https://discord.com/invite/qZDcQdEFqQ")
                                    .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://discord.com/invite/qZDcQdEFqQ"))
                                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to join the Paradigm Discord!"))))));
                    platform.sendSystemMessage(player, Text.literal(separator));
                    platform.sendSystemMessage(player, Text.literal("§d§lModules:"));

                    for (ParadigmModule mod : Paradigm.getModules()) {
                        boolean enabled = mod.isEnabled(services);
                        String color = enabled ? "§a" : "§7";
                        String symbol = enabled ? "✔" : "✖";
                        String nameColor = enabled ? "§f" : "§8";
                        String status = enabled ? "§7(enabled)" : "§8(disabled)";
                        String lineText = color + symbol + " " + nameColor + mod.getName() + " " + status;
                        Text line = Text.literal(lineText)
                                .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/paradigm help " + mod.getName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click for help about " + mod.getName()))));
                        platform.sendSystemMessage(player, line);
                    }

                    platform.sendSystemMessage(player, Text.literal(separator));
                    platform.sendSystemMessage(player, Text.literal("§eType §d/paradigm help <module> §efor details, or click a module above.")
                            .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/paradigm help "))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to start typing a help command")))));
                    platform.sendSystemMessage(player, Text.literal("§d§lParadigm - Elevate your server! ✨"));
                    platform.sendSystemMessage(player, Text.literal(separator));

                    return 1;
                })
                .then(CommandManager.literal("help")
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getEntity() instanceof ServerPlayerEntity sp ? sp : null;
                            if (player == null) {
                                ctx.getSource().sendFeedback(() -> Text.literal("Paradigm: Use /paradigm help <module> in-game."), false);
                                return 1;
                            }
                            platform.sendSystemMessage(player, Text.literal("§b§l[ Paradigm Help ]"));
                            platform.sendSystemMessage(player, Text.literal("§7Modules:"));
                            for (ParadigmModule mod : Paradigm.getModules()) {
                                boolean enabled = mod.isEnabled(services);
                                String color = enabled ? "§a" : "§7";
                                String symbol = enabled ? "✔" : "✖";
                                String nameColor = enabled ? "§f" : "§8";
                                String status = enabled ? "§7(enabled)" : "§8(disabled)";
                                String lineText = color + symbol + " " + nameColor + mod.getName() + " " + status;
                                Text line = Text.literal(lineText)
                                        .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/paradigm help " + mod.getName()))
                                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click for details"))));
                                platform.sendSystemMessage(player, line);
                            }
                            platform.sendSystemMessage(player, Text.literal("§7Type §e/paradigm help <module> §7for details."));
                            return 1;
                        })
                        .then(CommandManager.argument("module", StringArgumentType.word()).suggests(moduleSuggestions)
                                .executes(ctx -> {
                                    ServerPlayerEntity player = ctx.getSource().getEntity() instanceof ServerPlayerEntity sp ? sp : null;
                                    if (player == null) {
                                        ctx.getSource().sendFeedback(() -> Text.literal("Paradigm: Must be a player to view rich help."), false);
                                        return 1;
                                    }
                                    String modName = StringArgumentType.getString(ctx, "module");
                                    String helpMsg = moduleHelps.getOrDefault(modName, "No help available for " + modName + ".");
                                    platform.sendSystemMessage(player, Text.literal("§b§l[ Paradigm Module Help ]"));
                                    platform.sendSystemMessage(player, Text.literal("§b" + modName + " Help"));
                                    platform.sendSystemMessage(player, Text.literal(helpMsg));
                                    return 1;
                                }))
                )
        );
    }
}
