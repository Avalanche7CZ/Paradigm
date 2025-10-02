package eu.avalanche7.paradigm.modules.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.utils.MessageParser;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

import java.util.*;

public class Help implements ParadigmModule {
    private static final String NAME = "Help";

    @Override public String getName() { return NAME; }
    @Override public boolean isEnabled(Services services) { return true; }
    @Override public void onLoad(Object e, Services s, Object b) {}
    @Override public void onServerStarting(Object e, Services s) {}
    @Override public void onEnable(Services s) {}
    @Override public void onDisable(Services s) {}
    @Override public void onServerStopping(Object e, Services s) {}
    @Override public void registerEventListeners(Object bus, Services s) {}

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        Map<String, String> moduleHelps = buildModuleHelpMap();
        SuggestionProvider<ServerCommandSource> moduleSuggestions = (ctx, builder) -> {
            for (ParadigmModule m : Paradigm.getModules()) builder.suggest(m.getName());
            builder.suggest("all");
            builder.suggest("list");
            builder.suggest("search");
            return builder.buildFuture();
        };

        dispatcher.register(CommandManager.literal("paradigm")
            .executes(ctx -> {
                ServerPlayerEntity p = getPlayer(ctx.getSource());
                if (p == null) { ctx.getSource().sendFeedback(() -> Text.literal("Paradigm: Use this in-game."), false); return 1; }
                sendMainHelp(p, platform, services);
                return 1;
            })
            .then(CommandManager.literal("help")
                .executes(ctx -> {
                    ServerPlayerEntity p = getPlayer(ctx.getSource());
                    if (p == null) { ctx.getSource().sendFeedback(() -> Text.literal("Paradigm: Use in-game."), false); return 1; }
                    sendMainHelp(p, platform, services);
                    return 1;
                })
                .then(CommandManager.argument("arg", StringArgumentType.greedyString()).suggests(moduleSuggestions)
                    .executes(ctx -> {
                        ServerPlayerEntity p = getPlayer(ctx.getSource());
                        if (p == null) { ctx.getSource().sendFeedback(() -> Text.literal("Paradigm: Use in-game."), false); return 1; }
                        String raw = StringArgumentType.getString(ctx, "arg").trim();
                        if (raw.equalsIgnoreCase("list")) { sendModuleList(p, platform, services, false); return 1; }
                        if (raw.equalsIgnoreCase("all")) { sendAllModuleDetails(p, platform, services, moduleHelps); return 1; }
                        if (raw.toLowerCase(Locale.ROOT).startsWith("search ")) { String q = raw.substring(7).trim(); sendSearchResults(p, platform, services, q, moduleHelps); return 1; }
                        showModuleDetail(p, platform, services, raw, moduleHelps);
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

    private ServerPlayerEntity getPlayer(ServerCommandSource source) { return source.getEntity() instanceof ServerPlayerEntity sp ? sp : null; }

    private void sendMainHelp(ServerPlayerEntity p, IPlatformAdapter platform, Services services) {
        IPlayer ip = platform.wrapPlayer(p);
        MessageParser parser = services.getMessageParser();
        String version = Paradigm.getModVersion();
        IComponent sep = parser.parseMessage("&8&m----------------------------------------", ip);
        platform.sendSystemMessage(p, sep.getOriginalText());
        platform.sendSystemMessage(p, parser.parseMessage("&d&l[ Paradigm Mod v" + version + " ]", ip).getOriginalText());
        platform.sendSystemMessage(p, parser.parseMessage("&6by Avalanche7CZ &d♥", ip).getOriginalText());
        platform.sendSystemMessage(p, parser.parseMessage("&bDiscord: [hover=Click to join the Paradigm Discord!]https://discord.com/invite/qZDcQdEFqQ[/hover]", ip).getOriginalText());
        platform.sendSystemMessage(p, sep.getOriginalText());
        sendModuleList(p, platform, services, true);
        platform.sendSystemMessage(p, sep.getOriginalText());
        IComponent hint = parser.parseMessage("&eType &d/paradigm help <module> &efor details, or click a module above.", ip)
            .onClickSuggestCommand("/paradigm help ")
            .onHoverText("Click to start typing a help command");
        platform.sendSystemMessage(p, hint.getOriginalText());
        platform.sendSystemMessage(p, parser.parseMessage("&d&lParadigm - Elevate your server! ✨", ip).getOriginalText());
        platform.sendSystemMessage(p, sep.getOriginalText());
    }

    private void sendHelpOverview(ServerPlayerEntity p, IPlatformAdapter platform) {
        MessageParser parser = eu.avalanche7.paradigm.Paradigm.getServices().getMessageParser();
        IPlayer ip = platform.wrapPlayer(p);
        platform.sendSystemMessage(p, parser.parseMessage("&b&l[ Paradigm Help ]", ip).getOriginalText());
        platform.sendSystemMessage(p, parser.parseMessage("&7Available Modules:", ip).getOriginalText());
        List<ParadigmModule> mods = new ArrayList<>(Paradigm.getModules());
        mods.sort(Comparator.comparing(ParadigmModule::getName, String.CASE_INSENSITIVE_ORDER));
        for (ParadigmModule mod : mods) {
            String modName = mod.getName();
            IComponent line = parser.parseMessage("&b- " + modName, ip)
                .onClickSuggestCommand("/paradigm help " + modName)
                .onHoverText("Click for help about " + modName);
            platform.sendSystemMessage(p, line.getOriginalText());
        }
        platform.sendSystemMessage(p, parser.parseMessage("&7Click a module or type &d/paradigm help <module> &7for details.", ip).getOriginalText());
    }

    private void sendModuleList(ServerPlayerEntity p, IPlatformAdapter platform, Services services, boolean interactive) {
        MessageParser parser = services.getMessageParser();
        IPlayer ip = platform.wrapPlayer(p);
        platform.sendSystemMessage(p, parser.parseMessage("&d&lModules:", ip).getOriginalText());
        List<ParadigmModule> mods = new ArrayList<>(Paradigm.getModules());
        mods.sort(Comparator.comparing(ParadigmModule::getName, String.CASE_INSENSITIVE_ORDER));
        for (ParadigmModule mod : mods) {
            boolean enabled = mod.isEnabled(services);
            String symbol = enabled ? "✔" : "✖";
            String symbolColor = enabled ? "&a" : "&7";
            String nameColor = enabled ? "&f" : "&8";
            String status = enabled ? "&7(enabled)" : "&8(disabled)";
            String raw = symbolColor + symbol + " " + nameColor + mod.getName() + " " + status;
            IComponent line = parser.parseMessage(raw, ip);
            if (interactive) {
                line = line.onClickSuggestCommand("/paradigm help " + mod.getName())
                        .onHoverText("Click for help about " + mod.getName());
            }
            platform.sendSystemMessage(p, line.getOriginalText());
        }
    }

    private void showModuleDetail(ServerPlayerEntity p, IPlatformAdapter platform, Services services, String name, Map<String,String> moduleHelps) {
        String key = moduleHelps.keySet().stream().filter(k -> k.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (key == null) { platform.sendSystemMessage(p, Text.literal("§cNo help available for " + name + ". Try checking the wiki or ask on Discord.")); suggestClosest(p, platform, moduleHelps, name); return; }
        boolean enabled = Paradigm.getModules().stream()
                .filter(m -> m.getName().equalsIgnoreCase(key))
                .findFirst()
                .map(m -> m.isEnabled(services))
                .orElse(false); 
        MessageParser parser = services.getMessageParser();
        IPlayer ip = platform.wrapPlayer(p);
        platform.sendSystemMessage(p, parser.parseMessage("&b&l[ Paradigm Module Help ]", ip).getOriginalText());
        platform.sendSystemMessage(p, parser.parseMessage("&b" + key + " Help", ip).getOriginalText());
        for (String line : wrap(moduleHelps.get(key))) {
            platform.sendSystemMessage(p, parser.parseMessage("&7" + line, ip).getOriginalText());
        }
        String status = enabled ? "&a✔ &7Module is enabled" : "&7✖ &8Module is disabled";
        platform.sendSystemMessage(p, parser.parseMessage(status, ip).getOriginalText());
    }

    private void sendAllModuleDetails(ServerPlayerEntity p, IPlatformAdapter platform, Services services, Map<String,String> moduleHelps) {
        List<String> ordered = new ArrayList<>(moduleHelps.keySet());
        ordered.sort(String.CASE_INSENSITIVE_ORDER);
        platform.sendSystemMessage(p, Text.literal("§d§lAll Module Details:"));
        for (String k : ordered) showModuleDetail(p, platform, services, k, moduleHelps);
    }

    private void sendSearchResults(ServerPlayerEntity p, IPlatformAdapter platform, Services services, String query, Map<String,String> moduleHelps) {
        if (query.isEmpty()) { platform.sendSystemMessage(p, Text.literal("§cSearch query cannot be empty.")); return; }
        List<String> matches = moduleHelps.keySet().stream().filter(k -> k.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (matches.isEmpty()) { platform.sendSystemMessage(p, Text.literal("§cNo modules matched: §f" + query)); return; }
        platform.sendSystemMessage(p, Text.literal("§dMatches (§f" + matches.size() + "§d):"));
        for (String m : matches) {
            boolean enabled = Paradigm.getModules().stream().anyMatch(pm -> pm.getName().equalsIgnoreCase(m) && pm.isEnabled(services));
            Text line = Text.literal((enabled ? "§a" : "§7") + m)
                .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/paradigm help " + m))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click for detail"))));
            platform.sendSystemMessage(p, line);
        }
    }

    private void suggestClosest(ServerPlayerEntity p, IPlatformAdapter platform, Map<String,String> moduleHelps, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        String best = null; int bestDist = Integer.MAX_VALUE;
        for (String k : moduleHelps.keySet()) {
            int d = levenshtein(lower, k.toLowerCase(Locale.ROOT));
            if (d < bestDist) { bestDist = d; best = k; }
        }
        final String suggestion = best;
        if (suggestion != null && bestDist <= 4) {
            platform.sendSystemMessage(p, Text.literal("§7Did you mean: §d" + suggestion)
                .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/paradigm help " + suggestion))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Show " + suggestion + " help")))));
        }
    }

    private List<String> wrap(String text) {
        final int width = 70;
        List<String> out = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            if (line.length() + w.length() + 1 > width) { out.add(line.toString()); line.setLength(0); }
            if (!line.isEmpty()) line.append(' ');
            line.append(w);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length()+1][b.length()+1];
        for (int i=0;i<=a.length();i++) dp[i][0]=i;
        for (int j=0;j<=b.length();j++) dp[0][j]=j;
        for (int i=1;i<=a.length();i++) for (int j=1;j<=b.length();j++) {
            int cost = a.charAt(i-1)==b.charAt(j-1)?0:1;
            dp[i][j]=Math.min(Math.min(dp[i-1][j]+1, dp[i][j-1]+1), dp[i-1][j-1]+cost);
        }
        return dp[a.length()][b.length()];
    }
}
