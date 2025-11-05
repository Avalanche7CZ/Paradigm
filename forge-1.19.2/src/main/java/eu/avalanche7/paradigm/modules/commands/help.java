package eu.avalanche7.paradigm.modules.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.MessageParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class help implements ParadigmModule {
    private static final String NAME = "Help";

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
    }

    @Override public void onServerStarting(ServerStartingEvent event, Services services) {}
    @Override public void onEnable(Services services) {}
    @Override public void onDisable(Services services) {}
    @Override public void onServerStopping(ServerStoppingEvent event, Services services) {}

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
    }

    @Override
    @SuppressWarnings("unchecked")
    public void registerCommands(CommandDispatcher<?> dispatcher, Services services) {
        CommandDispatcher<CommandSourceStack> d = (CommandDispatcher<CommandSourceStack>) dispatcher;
        Map<String, String> moduleHelps = buildModuleHelpMap();

        SuggestionProvider<CommandSourceStack> moduleSuggestions = (ctx, builder) -> {
            for (ParadigmModule m : Paradigm.getModulesStatic()) builder.suggest(m.getName());
            builder.suggest("all");
            builder.suggest("list");
            builder.suggest("search");
            return builder.buildFuture();
        };

        d.register(Commands.literal("paradigm")
            .executes(ctx -> {
                ICommandSource src = services.getPlatformAdapter().wrapCommandSource(ctx.getSource());
                IPlayer p = src.getPlayer();
                if (p == null) {
                    services.getPlatformAdapter().sendFailure(src, services.getPlatformAdapter().createLiteralComponent("Paradigm: Use this in-game."));
                    return 1;
                }
                sendMainHelp(p, services);
                return 1;
            })
            .then(Commands.literal("help")
                .executes(ctx -> {
                    ICommandSource src = services.getPlatformAdapter().wrapCommandSource(ctx.getSource());
                    IPlayer p = src.getPlayer();
                    if (p == null) {
                        services.getPlatformAdapter().sendFailure(src, services.getPlatformAdapter().createLiteralComponent("Paradigm: Use in-game."));
                        return 1;
                    }
                    sendMainHelp(p, services);
                    return 1;
                })
                .then(Commands.argument("arg", StringArgumentType.greedyString()).suggests(moduleSuggestions)
                    .executes(ctx -> {
                        ICommandSource src = services.getPlatformAdapter().wrapCommandSource(ctx.getSource());
                        IPlayer p = src.getPlayer();
                        if (p == null) {
                            services.getPlatformAdapter().sendFailure(src, services.getPlatformAdapter().createLiteralComponent("Paradigm: Use in-game."));
                            return 1;
                        }
                        String raw = StringArgumentType.getString(ctx, "arg").trim();
                        if (raw.equalsIgnoreCase("list")) { sendModuleList(p, services, true); return 1; }
                        if (raw.equalsIgnoreCase("all")) { sendAllModuleDetails(p, services, moduleHelps); return 1; }
                        if (raw.toLowerCase(Locale.ROOT).startsWith("search ")) { String q = raw.substring(7).trim(); sendSearchResults(p, services, q, moduleHelps); return 1; }
                        showModuleDetail(p, services, raw, moduleHelps);
                        return 1;
                    })
                )
            )
        );
    }

    private Map<String, String> buildModuleHelpMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("GroupChat", "GroupChat lets players create private groups with their own chat channel. Commands: /groupchat create <name>, invite <player>, join <name>, say <msg>, toggle, leave, list, info [name], request <name>, accept <group>, deny <group>. Use invites or requests to manage membership.");
        m.put("StaffChat", "StaffChat provides a private channel for staff members. Use /sc toggle to switch, or /sc <message> to send one-off staff messages.");
        m.put("Mentions", "Mentions adds @player and @everyone highlighting. Configure cooldowns & permissions in mention config. Command: /mention <message> (if enabled).");
        m.put("Announcements", "Scheduled or manual broadcasts to chat, action bar, title, or boss bar. Commands: /paradigm broadcast|actionbar|title|bossbar <text> (requires permission). Schedules defined in announcements config.");
        m.put("MOTD", "Displays a configurable Message Of The Day when players join. Manage content in motd config. No direct commands.");
        m.put("Restart", "Handles timed restarts with warning countdowns. Commands: /restart now, /restart cancel. Times configured in restart config.");
        m.put("CustomCommands", "Create simple scripted commands via configuration. Reload with /paradigm reload customcommands.");
        m.put("Reload", "Reload specific configuration segments. Command: /paradigm reload <main|announcements|chat|motd|mention|restart|customcommands|all>.");
        m.put("Help", "Shows this interactive help system. Command: /paradigm help [module|all|list|search <query>].");
        return m;
    }

    private void sendMainHelp(IPlayer player, Services services) {
        MessageParser parser = services.getMessageParser();
        String version = getModVersion();
        IComponent sep = parser.parseMessage("&8&m----------------------------------------", player);
        services.getPlatformAdapter().sendSystemMessage(player, sep);
        services.getPlatformAdapter().sendSystemMessage(player, parser.parseMessage("&d&l[ Paradigm Mod v" + version + " ]", player));
        services.getPlatformAdapter().sendSystemMessage(player, parser.parseMessage("&6by Avalanche7CZ &d♥", player));
        IComponent discord = services.getPlatformAdapter().createLiteralComponent("§bDiscord: ")
            .append(services.getPlatformAdapter().createLiteralComponent("§9https://discord.com/invite/qZDcQdEFqQ")
                .onClickOpenUrl("https://discord.com/invite/qZDcQdEFqQ")
                .onHoverText("Click to join the Paradigm Discord!"));
        services.getPlatformAdapter().sendSystemMessage(player, discord);
        services.getPlatformAdapter().sendSystemMessage(player, sep);
        sendModuleList(player, services, true);
        services.getPlatformAdapter().sendSystemMessage(player, sep);
        IComponent hint = parser.parseMessage("&eType &d/paradigm help <module> &efor details, or click a module above.", player)
            .onClickSuggestCommand("/paradigm help ")
            .onHoverText("Click to start typing a help command");
        services.getPlatformAdapter().sendSystemMessage(player, hint);
        services.getPlatformAdapter().sendSystemMessage(player, parser.parseMessage("&d&lParadigm - Elevate your server! ✨", player));
        services.getPlatformAdapter().sendSystemMessage(player, sep);
    }

    private void sendModuleList(IPlayer player, Services services, boolean interactive) {
        MessageParser parser = services.getMessageParser();
        services.getPlatformAdapter().sendSystemMessage(player, parser.parseMessage("&d&lModules:", player));
        List<ParadigmModule> mods = new ArrayList<>(Paradigm.getModulesStatic());
        mods.sort(Comparator.comparing(ParadigmModule::getName, String.CASE_INSENSITIVE_ORDER));
        for (ParadigmModule mod : mods) {
            boolean enabled = mod.isEnabled(services);
            String symbol = enabled ? "✔" : "✖";
            String symbolColor = enabled ? "&a" : "&7";
            String nameColor = enabled ? "&f" : "&8";
            String status = enabled ? "&7(enabled)" : "&8(disabled)";
            String raw = symbolColor + symbol + " " + nameColor + mod.getName() + " " + status;
            IComponent line = parser.parseMessage(raw, player);
            if (interactive) {
                line = line.onClickSuggestCommand("/paradigm help " + mod.getName())
                        .onHoverText("Click for help about " + mod.getName());
            }
            services.getPlatformAdapter().sendSystemMessage(player, line);
        }
    }

    private void showModuleDetail(IPlayer player, Services services, String name, Map<String,String> moduleHelps) {
        String key = moduleHelps.keySet().stream().filter(k -> k.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (key == null) {
            services.getPlatformAdapter().sendSystemMessage(player, services.getPlatformAdapter().createLiteralComponent("§cNo help available for " + name + ". Try checking the wiki or ask on Discord."));
            suggestClosest(player, services, moduleHelps, name);
            return;
        }
        boolean enabled = Paradigm.getModulesStatic().stream()
                .filter(m -> m.getName().equalsIgnoreCase(key))
                .findFirst()
                .map(m -> m.isEnabled(services))
                .orElse(false);
        MessageParser parser = services.getMessageParser();
        services.getPlatformAdapter().sendSystemMessage(player, parser.parseMessage("&b&l[ Paradigm Module Help ]", player));
        services.getPlatformAdapter().sendSystemMessage(player, parser.parseMessage("&b" + key + " Help", player));
        for (String line : wrap(moduleHelps.get(key))) {
            services.getPlatformAdapter().sendSystemMessage(player, parser.parseMessage("&7" + line, player));
        }
        String status = enabled ? "&a✔ &7Module is enabled" : "&7✖ &8Module is disabled";
        services.getPlatformAdapter().sendSystemMessage(player, parser.parseMessage(status, player));
    }

    private void sendAllModuleDetails(IPlayer player, Services services, Map<String,String> moduleHelps) {
        List<String> ordered = new ArrayList<>(moduleHelps.keySet());
        ordered.sort(String.CASE_INSENSITIVE_ORDER);
        services.getPlatformAdapter().sendSystemMessage(player, services.getPlatformAdapter().createLiteralComponent("§d§lAll Module Details:"));
        for (String k : ordered) showModuleDetail(player, services, k, moduleHelps);
    }

    private void sendSearchResults(IPlayer player, Services services, String query, Map<String,String> moduleHelps) {
        if (query.isEmpty()) { services.getPlatformAdapter().sendSystemMessage(player, services.getPlatformAdapter().createLiteralComponent("§cSearch query cannot be empty.")); return; }
        List<String> matches = moduleHelps.keySet().stream().filter(k -> k.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (matches.isEmpty()) { services.getPlatformAdapter().sendSystemMessage(player, services.getPlatformAdapter().createLiteralComponent("§cNo modules matched: §f" + query)); return; }
        services.getPlatformAdapter().sendSystemMessage(player, services.getPlatformAdapter().createLiteralComponent("§dMatches (§f" + matches.size() + "§d):"));
        for (String m : matches) {
            boolean enabled = Paradigm.getModulesStatic().stream().anyMatch(pm -> pm.getName().equalsIgnoreCase(m) && pm.isEnabled(services));
            String text = (enabled ? "§a" : "§7") + m;
            IComponent line = services.getPlatformAdapter().createLiteralComponent(text)
                .onClickSuggestCommand("/paradigm help " + m)
                .onHoverText("Click for detail");
            services.getPlatformAdapter().sendSystemMessage(player, line);
        }
    }

    private void suggestClosest(IPlayer player, Services services, Map<String,String> moduleHelps, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        String best = null; int bestDist = Integer.MAX_VALUE;
        for (String k : moduleHelps.keySet()) {
            int d = levenshtein(lower, k.toLowerCase(Locale.ROOT));
            if (d < bestDist) { bestDist = d; best = k; }
        }
        if (best != null && bestDist <= 4) {
            IComponent line = services.getPlatformAdapter().createLiteralComponent("§7Did you mean: §d" + best)
                .onClickSuggestCommand("/paradigm help " + best)
                .onHoverText("Show " + best + " help");
            services.getPlatformAdapter().sendSystemMessage(player, line);
        }
    }

    private List<String> wrap(String text) {
        final int width = 70;
        List<String> out = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            if (line.length() + w.length() + 1 > width) { out.add(line.toString()); line.setLength(0); }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length()+1][b.length()+1];
        for (int i=0;i<=a.length();i++) dp[i][0]=i;
        for (int j=0;j<=b.length();j++) dp[0][j]=j;
        for (int i=1;i<=a.length();i++) for (int j=1;j<=b.length();j++) {
            int cost = a.charAt(i-1)==b.charAt(j-1)?0:1;
            int del = dp[i-1][j] + 1;
            int ins = dp[i][j-1] + 1;
            int sub = dp[i-1][j-1] + cost;
            dp[i][j] = Math.min(Math.min(del, ins), sub);
        }
        return dp[a.length()][b.length()];
    }

    private String getModVersion() {
        return ModList.get().getModContainerById("paradigm")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("?");
    }
}
