package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.MessageParser;

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
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        Map<String, String> moduleHelps = buildModuleHelpMap();

        List<String> baseSuggestions = new ArrayList<>();
        try {
            List<ParadigmModule> modules = ParadigmAPI.getModules();
            if (modules != null) {
                for (ParadigmModule m : modules) {
                    baseSuggestions.add(m.getName());
                }
            }
        } catch (Exception ignored) {
        }
        baseSuggestions.add("all");
        baseSuggestions.add("list");
        baseSuggestions.add("search");

        ICommandBuilder cmd = platform.createCommandBuilder()
                .literal("paradigm")
                .executes(ctx -> {
                    IPlayer p = ctx.getSource().getPlayer();
                    if (p == null) {
                        platform.sendFailure(ctx.getSource(), platform.createLiteralComponent("Paradigm: Use this in-game."));
                        return 1;
                    }
                    sendMainHelp(p, platform, services);
                    return 1;
                })
                .then(platform.createCommandBuilder()
                        .literal("help")
                        .executes(ctx -> {
                            IPlayer p = ctx.getSource().getPlayer();
                            if (p == null) {
                                platform.sendFailure(ctx.getSource(), platform.createLiteralComponent("Paradigm: Use this in-game."));
                                return 1;
                            }
                            sendMainHelp(p, platform, services);
                            return 1;
                        })
                        .then(platform.createCommandBuilder()
                                .argument("arg", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .suggests((c, input) -> baseSuggestions)
                                .executes(ctx -> {
                                    IPlayer p = ctx.getSource().getPlayer();
                                    if (p == null) {
                                        platform.sendFailure(ctx.getSource(), platform.createLiteralComponent("Paradigm: Use this in-game."));
                                        return 1;
                                    }

                                    String raw = ctx.getStringArgument("arg");
                                    if (raw == null) raw = "";
                                    raw = raw.trim();

                                    if (raw.equalsIgnoreCase("list")) {
                                        sendModuleList(p, platform, services, false);
                                        return 1;
                                    }
                                    if (raw.equalsIgnoreCase("all")) {
                                        sendAllModuleDetails(p, platform, services, moduleHelps);
                                        return 1;
                                    }
                                    if (raw.toLowerCase(Locale.ROOT).startsWith("search ")) {
                                        String q = raw.substring(7).trim();
                                        sendSearchResults(p, platform, services, q, moduleHelps);
                                        return 1;
                                    }
                                    showModuleDetail(p, platform, services, raw, moduleHelps);
                                    return 1;
                                })));

        platform.registerCommand(cmd);
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

    private void sendMainHelp(IPlayer p, IPlatformAdapter platform, Services services) {
        MessageParser parser = services.getMessageParser();
        String version = ParadigmAPI.getModVersion();

        IComponent sep = parser.parseMessage("<gradient:#334155:#64748B>&m----------------------------------------</gradient>", p);
        platform.sendSystemMessage(p, sep);

        platform.sendSystemMessage(p, parser.parseMessage(
                "<center><bold><gradient:#22D3EE:#A78BFA>Paradigm</gradient></bold> <color:#94A3B8>v" + version + "</color></center>",
                p
        ));

        platform.sendSystemMessage(p, parser.parseMessage(
                "<center><color:#F472B6>by</color> <color:#F8FAFC>Avalanche7CZ</color> <color:#F472B6>♥</color></center>",
                p
        ));

        platform.sendSystemMessage(p, parser.parseMessage(
                "<center><color:#94A3B8>Discord:</color> " +
                        "<hover:'<color:#F8FAFC>Click to join the Paradigm Discord</color>'>" +
                        "<click:open_url:'https://discord.com/invite/qZDcQdEFqQ'>" +
                        "<underline><color:#38BDF8>discord.gg/qZDcQdEFqQ</color></underline>" +
                        "</click></hover></center>",
                p
        ));

        platform.sendSystemMessage(p, sep);
        sendModuleList(p, platform, services, true);
        platform.sendSystemMessage(p, sep);

        IComponent hint = parser.parseMessage(
                        "<color:#FBBF24>Tip:</color> <color:#F8FAFC>Type</color> <color:#FBBF24><bold>/paradigm help &lt;module&gt;</bold></color> <color:#F8FAFC>for details, or click a module above.</color>",
                        p)
                .onClickSuggestCommand("/paradigm help ")
                .onHoverText("Click to start typing a help command");
        platform.sendSystemMessage(p, hint);

        platform.sendSystemMessage(p, parser.parseMessage(
                "<center><gradient:#F472B6:#A78BFA><bold>Paradigm</bold></gradient> <color:#94A3B8>— elevate your server</color></center>",
                p
        ));
        platform.sendSystemMessage(p, sep);
    }

    private void sendModuleList(IPlayer p, IPlatformAdapter platform, Services services, boolean interactive) {
        MessageParser parser = services.getMessageParser();
        platform.sendSystemMessage(p, parser.parseMessage("<bold><gradient:#F472B6:#A78BFA>Modules</gradient></bold><color:#94A3B8>:</color>", p));

        List<ParadigmModule> mods = new ArrayList<>(ParadigmAPI.getModules());
        mods.sort(Comparator.comparing(ParadigmModule::getName, String.CASE_INSENSITIVE_ORDER));

        for (ParadigmModule mod : mods) {
            boolean enabled = mod.isEnabled(services);

            String symbol = enabled ? "✔" : "✖";
            String symbolColor = enabled ? "<color:#22C55E>" : "<color:#94A3B8>";
            String nameColor = enabled ? "<color:#F8FAFC>" : "<color:#64748B>";
            String status = enabled ? "<color:#94A3B8>(enabled)</color>" : "<color:#475569>(disabled)</color>";

            String raw = symbolColor + symbol + "</color> " + nameColor + mod.getName() + "</color> " + status;
            IComponent line = parser.parseMessage(raw, p);

            if (interactive) {
                // Suggest command is nicer than run-command here.
                line = line.onClickSuggestCommand("/paradigm help " + mod.getName())
                        .onHoverText("Click for help about " + mod.getName());
            }
            platform.sendSystemMessage(p, line);
        }
    }

    private void showModuleDetail(IPlayer p, IPlatformAdapter platform, Services services, String name, Map<String,String> moduleHelps) {
        String key = moduleHelps.keySet().stream().filter(k -> k.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (key == null) {
            platform.sendSystemMessage(p, platform.createComponentFromLiteral("§cNo help available for " + name + ". Try checking the wiki or ask on Discord."));
            suggestClosest(p, platform, moduleHelps, name);
            return;
        }
        boolean enabled = ParadigmAPI.getModules().stream()
                .filter(m -> m.getName().equalsIgnoreCase(key))
                .findFirst()
                .map(m -> m.isEnabled(services))
                .orElse(false);
        MessageParser parser = services.getMessageParser();
        platform.sendSystemMessage(p, parser.parseMessage("&b&l[ Paradigm Module Help ]", p));
        platform.sendSystemMessage(p, parser.parseMessage("&b" + key + " Help", p));
        for (String line : wrap(moduleHelps.get(key))) {
            platform.sendSystemMessage(p, parser.parseMessage("&7" + line, p));
        }
        String status = enabled ? "&a✔ &7Module is enabled" : "&7✖ &8Module is disabled";
        platform.sendSystemMessage(p, parser.parseMessage(status, p));
    }

    private void sendAllModuleDetails(IPlayer p, IPlatformAdapter platform, Services services, Map<String,String> moduleHelps) {
        List<String> ordered = new ArrayList<>(moduleHelps.keySet());
        ordered.sort(String.CASE_INSENSITIVE_ORDER);
        platform.sendSystemMessage(p, platform.createComponentFromLiteral("§d§lAll Module Details:"));
        for (String k : ordered) showModuleDetail(p, platform, services, k, moduleHelps);
    }

    private void sendSearchResults(IPlayer p, IPlatformAdapter platform, Services services, String query, Map<String,String> moduleHelps) {
        if (query == null) query = "";
        query = query.trim();
        final String qLower = query.toLowerCase(Locale.ROOT);

        if (query.isEmpty()) {
            platform.sendSystemMessage(p, platform.createComponentFromLiteral("§cSearch query cannot be empty."));
            return;
        }
        List<String> matches = moduleHelps.keySet().stream()
                .filter(k -> k.toLowerCase(Locale.ROOT).contains(qLower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        if (matches.isEmpty()) {
            platform.sendSystemMessage(p, platform.createComponentFromLiteral("§cNo modules matched: §f" + query));
            return;
        }
        platform.sendSystemMessage(p, platform.createComponentFromLiteral("§dMatches (§f" + matches.size() + "§d):"));
        for (String m : matches) {
            boolean enabled = ParadigmAPI.getModules().stream().anyMatch(pm -> pm.getName().equalsIgnoreCase(m) && pm.isEnabled(services));
            String text = (enabled ? "§a" : "§7") + m;
            IComponent line = platform.createComponentFromLiteral(text)
                    .onClickSuggestCommand("/paradigm help " + m)
                    .onHoverText("Click for detail");
            platform.sendSystemMessage(p, line);
        }
    }

    private void suggestClosest(IPlayer p, IPlatformAdapter platform, Map<String,String> moduleHelps, String input) {
        String lower = (input == null ? "" : input).toLowerCase(Locale.ROOT);
        String best = null; int bestDist = Integer.MAX_VALUE;
        for (String k : moduleHelps.keySet()) {
            int d = levenshtein(lower, k.toLowerCase(Locale.ROOT));
            if (d < bestDist) { bestDist = d; best = k; }
        }
        if (best != null) {
            IComponent suggestion = platform.createComponentFromLiteral("§7Did you mean: §b" + best)
                    .onClickSuggestCommand("/paradigm help " + best)
                    .onHoverText("Click to show help for " + best);
            platform.sendSystemMessage(p, suggestion);
        }
    }

    private List<String> wrap(String text) {
        if (text == null) return List.of();
        int max = 52;
        List<String> out = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            if (line.length() + w.length() + 1 > max) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(w);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private int levenshtein(String a, String b) {
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j < costs.length; j++) costs[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]), a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }
}
