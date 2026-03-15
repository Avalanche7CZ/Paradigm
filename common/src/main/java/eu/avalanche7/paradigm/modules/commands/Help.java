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
    private static final String SEP = "<gradient:#334155:#64748B>&m----------------------------------------</gradient>";

    private record HelpEntry(String summary, String usage, String configHint) {}

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
        Map<String, HelpEntry> moduleHelps = buildModuleHelpMap();

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
                    Reload.refreshModuleStatesForHelp(services);
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
                            Reload.refreshModuleStatesForHelp(services);
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
                                    Reload.refreshModuleStatesForHelp(services);
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

    private Map<String, HelpEntry> buildModuleHelpMap() {
        Map<String, HelpEntry> m = new LinkedHashMap<>();
        m.put("GroupChat", new HelpEntry(
                "Private group channels with invites and join requests.",
                "/groupchat create [name], invite [player], join [name], say [message]",
                "groupchat.json"
        ));
        m.put("StaffChat", new HelpEntry(
                "Private staff-only chat for moderation communication.",
                "/sc toggle or /sc [message]",
                "chat.json"
        ));
        m.put("PrivateMessages", new HelpEntry(
                "Direct private messaging between players with quick reply support.",
                "/msg|/tell|/w|/whisper [player] [message], /reply|/r [message]",
                "chat.json"
        ));
        m.put("Mentions", new HelpEntry(
                "Highlights @player and @everyone with optional cooldowns.",
                "/mention [message]",
                "mention.json"
        ));
        m.put("Announcements", new HelpEntry(
                "Scheduled broadcasts in chat, actionbar, title, and bossbar.",
                "/paradigm broadcast|actionbar|title|bossbar [text]",
                "announcements.json"
        ));
        m.put("MOTD", new HelpEntry(
                "Shows a configurable MOTD when players join/server list refreshes.",
                "No direct command (config driven).",
                "motd.json"
        ));
        m.put("Restart", new HelpEntry(
                "Timed restart system with countdown and notifications.",
                "/restart now or /restart cancel",
                "restart.json"
        ));
        m.put("CustomCommands", new HelpEntry(
                "Create custom commands from config with cooldown support.",
                "Reload changes via /paradigm reload customcommands",
                "customcommands.json"
        ));
        m.put("Reload", new HelpEntry(
                "Reload one config or all configs without full restart.",
                "/paradigm reload [main|announcements|chat|motd|mention|restart|customcommands|all]",
                "main config + module configs"
        ));
        m.put("Help", new HelpEntry(
                "Interactive help with module list, details and search.",
                "/paradigm help [module|all|list|search query]",
                "N/A"
        ));
        return m;
    }

    private void sendMainHelp(IPlayer p, IPlatformAdapter platform, Services services) {
        MessageParser parser = services.getMessageParser();
        String version = ParadigmAPI.getModVersion();

        IComponent sep = parser.parseMessage(SEP, p);
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
                        "<color:#FBBF24>Tip:</color> <color:#F8FAFC>Type</color> <color:#FBBF24><bold>/paradigm help [module]</bold></color> <color:#F8FAFC>for details, or click a module above.</color>",
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
                line = line.onClickSuggestCommand("/paradigm help " + mod.getName())
                        .onHoverText("Click for help about " + mod.getName());
            }
            platform.sendSystemMessage(p, line);
        }
    }

    private void showModuleDetail(IPlayer p, IPlatformAdapter platform, Services services, String name, Map<String, HelpEntry> moduleHelps) {
        String key = moduleHelps.keySet().stream().filter(k -> k.equalsIgnoreCase(name)).findFirst().orElse(null);
        MessageParser parser = services.getMessageParser();
        if (key == null) {
            platform.sendSystemMessage(p, parser.parseMessage("<color:#EF4444>No help available for</color> <color:#F8FAFC>" + name + "</color><color:#EF4444>.</color>", p));
            suggestClosest(p, platform, moduleHelps, name);
            return;
        }

        HelpEntry entry = moduleHelps.get(key);
        boolean enabled = ParadigmAPI.getModules().stream()
                .filter(m -> m.getName().equalsIgnoreCase(key))
                .findFirst()
                .map(m -> m.isEnabled(services))
                .orElse(false);

        platform.sendSystemMessage(p, parser.parseMessage(SEP, p));
        platform.sendSystemMessage(p, parser.parseMessage(
                "<bold><gradient:#F472B6:#A78BFA>" + key + "</gradient></bold> <color:#94A3B8>module</color>",
                p
        ));

        String status = enabled
                ? "<color:#22C55E>Enabled</color>"
                : "<color:#94A3B8>Disabled</color>";
        platform.sendSystemMessage(p, parser.parseMessage("<color:#94A3B8>Status:</color> " + status, p));

        for (String line : wrap(entry.summary())) {
            platform.sendSystemMessage(p, parser.parseMessage("<color:#CBD5E1>" + line + "</color>", p));
        }

        IComponent usage = parser.parseMessage(
                        "<color:#94A3B8>Usage:</color> <color:#F8FAFC>" + entry.usage() + "</color>",
                        p)
                .onClickSuggestCommand(toCommandSuggestion(entry.usage(), key))
                .onHoverText("Click to suggest this command");
        platform.sendSystemMessage(p, usage);

        platform.sendSystemMessage(p, parser.parseMessage(
                "<color:#94A3B8>Config:</color> <color:#38BDF8>" + entry.configHint() + "</color>",
                p
        ));
        platform.sendSystemMessage(p, parser.parseMessage(SEP, p));
    }

    private void sendAllModuleDetails(IPlayer p, IPlatformAdapter platform, Services services, Map<String, HelpEntry> moduleHelps) {
        List<String> ordered = new ArrayList<>(moduleHelps.keySet());
        ordered.sort(String.CASE_INSENSITIVE_ORDER);
        platform.sendSystemMessage(p, services.getMessageParser().parseMessage("<bold><gradient:#F472B6:#A78BFA>All Module Details</gradient></bold>", p));
        for (String k : ordered) showModuleDetail(p, platform, services, k, moduleHelps);
    }

    private void sendSearchResults(IPlayer p, IPlatformAdapter platform, Services services, String query, Map<String, HelpEntry> moduleHelps) {
        if (query == null) query = "";
        query = query.trim();
        final String qLower = query.toLowerCase(Locale.ROOT);
        MessageParser parser = services.getMessageParser();

        if (query.isEmpty()) {
            platform.sendSystemMessage(p, parser.parseMessage("<color:#EF4444>Search query cannot be empty.</color>", p));
            return;
        }
        List<String> matches = moduleHelps.keySet().stream()
                .filter(k -> {
                    HelpEntry entry = moduleHelps.get(k);
                    return k.toLowerCase(Locale.ROOT).contains(qLower)
                            || (entry != null && (
                            entry.summary().toLowerCase(Locale.ROOT).contains(qLower)
                                    || entry.usage().toLowerCase(Locale.ROOT).contains(qLower)
                    ));
                })
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        if (matches.isEmpty()) {
            platform.sendSystemMessage(p, parser.parseMessage("<color:#EF4444>No modules matched:</color> <color:#F8FAFC>" + query + "</color>", p));
            return;
        }
        platform.sendSystemMessage(p, parser.parseMessage("<bold><gradient:#F472B6:#A78BFA>Matches</gradient></bold> <color:#94A3B8>(" + matches.size() + ")</color>", p));
        for (String m : matches) {
            boolean enabled = ParadigmAPI.getModules().stream().anyMatch(pm -> pm.getName().equalsIgnoreCase(m) && pm.isEnabled(services));
            HelpEntry entry = moduleHelps.get(m);
            String color = enabled ? "#22C55E" : "#94A3B8";
            String text = "<color:" + color + ">" + m + "</color><color:#64748B> - " + (entry != null ? entry.summary() : "") + "</color>";
            IComponent line = parser.parseMessage(text, p)
                    .onClickSuggestCommand("/paradigm help " + m)
                    .onHoverText("Click for detail");
            platform.sendSystemMessage(p, line);
        }
    }

    private void suggestClosest(IPlayer p, IPlatformAdapter platform, Map<String, HelpEntry> moduleHelps, String input) {
        String lower = (input == null ? "" : input).toLowerCase(Locale.ROOT);
        String best = null; int bestDist = Integer.MAX_VALUE;
        for (String k : moduleHelps.keySet()) {
            int d = levenshtein(lower, k.toLowerCase(Locale.ROOT));
            if (d < bestDist) { bestDist = d; best = k; }
        }
        if (best != null) {
            IComponent suggestion = platform.createComponentFromLiteral("Did you mean: " + best)
                    .withColor("aqua")
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

    private String toCommandSuggestion(String usage, String moduleName) {
        if (usage == null || usage.isBlank()) return "/paradigm help " + moduleName;
        if (!usage.startsWith("/")) return "/paradigm help " + moduleName;

        int commaIdx = usage.indexOf(',');
        int orIdx = usage.indexOf(" or ");
        int endIdx;
        if (commaIdx >= 0 && orIdx >= 0) endIdx = Math.min(commaIdx, orIdx);
        else endIdx = Math.max(commaIdx, orIdx);

        String first = endIdx > 0 ? usage.substring(0, endIdx) : usage;
        return first.trim();
    }
}
