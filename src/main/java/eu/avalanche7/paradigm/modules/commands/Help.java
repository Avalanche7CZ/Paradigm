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

import java.util.*;
import java.util.stream.Collectors;

public class Help implements ParadigmModule {
    private static final String NAME = "Help";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("ParadigmHelp");

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
        String version = Paradigm.getModVersion();
        String sep = "§8§m----------------------------------------";
        platform.sendSystemMessage(p, Text.literal(sep));
        platform.sendSystemMessage(p, Text.literal("§d§lParadigm §7v" + version + "  §f(/paradigm help)"));
        platform.sendSystemMessage(p, Text.literal("§7Author: §dAvalanche7CZ  §7Discord: §9discord.gg/qZDcQdEFqQ")
            .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://discord.com/invite/qZDcQdEFqQ"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Open Discord")))));
        sendModuleList(p, platform, services, true);
        platform.sendSystemMessage(p, Text.literal(sep));
        platform.sendSystemMessage(p, Text.literal("§eHover & click a module for detail.  §7Try: §d/paradigm help all §7| §d/paradigm help search restart"));
        platform.sendSystemMessage(p, Text.literal(sep));
    }

    private void sendModuleList(ServerPlayerEntity p, IPlatformAdapter platform, Services services, boolean interactive) {
        List<ParadigmModule> mods = new ArrayList<>(Paradigm.getModules());
        mods.sort(Comparator.comparing(ParadigmModule::getName, String.CASE_INSENSITIVE_ORDER));
        platform.sendSystemMessage(p, Text.literal("§d§lModules:"));
        for (ParadigmModule mod : mods) {
            boolean enabled = mod.isEnabled(services);
            String color = enabled ? "§a" : "§7";
            String status = enabled ? "§2ON" : "§8OFF";
            Text line = Text.literal(color + mod.getName() + " §8[" + status + "§8]")
                .styled(s -> interactive ? s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/paradigm help " + mod.getName()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click for " + mod.getName() + " details"))) : s);
            platform.sendSystemMessage(p, line);
        }
    }

    private void showModuleDetail(ServerPlayerEntity p, IPlatformAdapter platform, Services services, String name, Map<String,String> moduleHelps) {
        String key = moduleHelps.keySet().stream().filter(k -> k.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (key == null) { platform.sendSystemMessage(p, Text.literal("§cUnknown module: §f" + name)); suggestClosest(p, platform, moduleHelps, name); return; }
        boolean enabled = Paradigm.getModules().stream().filter(m -> m.getName().equalsIgnoreCase(key)).findFirst().map(m -> m.isEnabled(services)).orElse(true);
        String header = (enabled ? "§a" : "§7") + key + " §8[" + (enabled ? "§2ENABLED" : "§8DISABLED") + "§8]";
        platform.sendSystemMessage(p, Text.literal("§8§m---------------§r §d" + key + " Help §8§m---------------"));
        platform.sendSystemMessage(p, Text.literal(header));
        for (String line : wrap(moduleHelps.get(key), 70)) {
            platform.sendSystemMessage(p, Text.literal("§7" + line));
        }
        platform.sendSystemMessage(p, Text.literal("§8--------------------------------------------------"));
    }

    private void sendAllModuleDetails(ServerPlayerEntity p, IPlatformAdapter platform, Services services, Map<String,String> moduleHelps) {
        List<String> ordered = new ArrayList<>(moduleHelps.keySet());
        ordered.sort(String.CASE_INSENSITIVE_ORDER);
        platform.sendSystemMessage(p, Text.literal("§d§lAll Module Details:"));
        for (String k : ordered) showModuleDetail(p, platform, services, k, moduleHelps);
    }

    private void sendSearchResults(ServerPlayerEntity p, IPlatformAdapter platform, Services services, String query, Map<String,String> moduleHelps) {
        if (query.isEmpty()) { platform.sendSystemMessage(p, Text.literal("§cSearch query cannot be empty.")); return; }
        List<String> matches = moduleHelps.keySet().stream().filter(k -> k.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        if (matches.isEmpty()) { platform.sendSystemMessage(p, Text.literal("§cNo modules matched: §f" + query)); return; }
        platform.sendSystemMessage(p, Text.literal("§dMatches (§f" + matches.size() + "§d):"));
        for (String m : matches) {
            boolean enabled = Paradigm.getModules().stream().anyMatch(pm -> pm.getName().equalsIgnoreCase(m) && pm.isEnabled(services));
            Text line = Text.literal((enabled ? "§a" : "§7") + m)
                .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/paradigm help " + m))
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
                .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/paradigm help " + suggestion))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Show " + suggestion + " help")))));
        }
    }

    private List<String> wrap(String text, int width) {
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
            dp[i][j]=Math.min(Math.min(dp[i-1][j]+1, dp[i][j-1]+1), dp[i-1][j-1]+cost);
        }
        return dp[a.length()][b.length()];
    }
}
