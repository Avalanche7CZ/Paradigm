package eu.avalanche7.paradigm.platform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.modules.commands.shared.CommandExecutionGuard;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder.ArgumentType;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

final class ForgeCommand extends CommandBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForgeCommand.class);
    private final String name;
    private final PlatformAdapterImpl platform;
    private final List<Contribution> roots = new ArrayList<>();

    ForgeCommand(ForgeCommandBuilder root, PlatformAdapterImpl platform) {
        this(root, platform, platform);
    }

    ForgeCommand(ForgeCommandBuilder root, PlatformAdapterImpl platform, Object contributor) {
        if (root.literalName() == null || root.argumentName() != null) {
            throw new IllegalArgumentException("A command root must be a literal");
        }
        this.name = root.literalName();
        this.platform = platform;
        roots.add(new Contribution(contributor, root));
    }

    void addRoot(ForgeCommandBuilder root) {
        addRoot(root, platform);
    }

    void addRoot(ForgeCommandBuilder root, Object contributor) {
        if (!name.equals(root.literalName())) {
            throw new IllegalArgumentException("Cannot combine different command roots");
        }
        roots.add(new Contribution(contributor, root));
    }

    boolean removeContributor(Object contributor) {
        roots.removeIf(root -> root.owner == contributor);
        return roots.isEmpty();
    }

    boolean isEmpty() {
        return roots.isEmpty();
    }

    boolean ownedBy(PlatformAdapterImpl owner) {
        return platform == owner;
    }

    @Override
    public String getCommandName() {
        return name;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/" + name;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        ICommandSource source = new MinecraftCommandSource(sender);
        for (Contribution contribution : roots) {
            if (allowed(contribution.root, source)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        ICommandSource source = new MinecraftCommandSource(sender);
        String input = name + (args.length == 0 ? "" : " " + String.join(" ", args));
        Match best = null;
        for (Contribution contribution : roots) {
            ForgeCommandBuilder root = contribution.root;
            if (!allowed(root, source)) {
                continue;
            }
            Match match = match(root, args, 0, new LinkedHashMap<>(), source, 0);
            if (match != null && (best == null || match.score > best.score)) {
                best = match;
            }
        }
        if (best == null) {
            platform.sendFailure(source, platform.createLiteralComponent(
                    hasDeniedPath(args, source) ? "§cYou do not have permission to use this command."
                            : "§cInvalid or incomplete command: " + input));
            return;
        }
        CommandExecutionGuard.execute(best.node.executor(),
                new ForgeCommandContext(source, best.arguments, input));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        ICommandSource source = new MinecraftCommandSource(sender);
        String input = name + " " + String.join(" ", args);
        Set<String> result = new LinkedHashSet<>();
        for (Contribution contribution : roots) {
            ForgeCommandBuilder root = contribution.root;
            if (allowed(root, source)) {
                suggest(root, args, 0, new LinkedHashMap<>(), source, input, result);
            }
        }
        return new ArrayList<>(result);
    }

    private Match match(ForgeCommandBuilder node, String[] args, int index,
            Map<String, Object> values, ICommandSource source, int score) {
        if (index == args.length) {
            return node.executor() == null ? null : new Match(node, values, score);
        }
        Match best = null;
        for (ForgeCommandBuilder child : node.children()) {
            if (!allowed(child, source)) {
                continue;
            }
            Parsed parsed = parse(child, args, index);
            if (parsed == null) {
                continue;
            }
            Map<String, Object> next = new LinkedHashMap<>(values);
            if (child.argumentName() != null) {
                next.put(child.argumentName(), parsed.value);
            }
            Match candidate = match(child, args, parsed.nextIndex, next, source,
                    score + (child.literalName() != null ? 100 : 1));
            if (candidate != null && (best == null || candidate.score > best.score)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean hasDeniedPath(String[] args, ICommandSource source) {
        for (Contribution contribution : roots) {
            if (deniedOnPath(contribution.root, args, 0, source)) {
                return true;
            }
        }
        return false;
    }

    private boolean deniedOnPath(ForgeCommandBuilder node, String[] args, int index,
            ICommandSource source) {
        if (!allowed(node, source)) {
            return true;
        }
        if (index == args.length) {
            return false;
        }
        for (ForgeCommandBuilder child : node.children()) {
            Parsed parsed = parse(child, args, index);
            if (parsed != null && deniedOnPath(child, args, parsed.nextIndex, source)) {
                return true;
            }
        }
        return false;
    }

    private Parsed parse(ForgeCommandBuilder node, String[] args, int index) {
        String token = args[index];
        if (node.literalName() != null) {
            return node.literalName().equals(token) ? new Parsed(null, index + 1) : null;
        }
        if (node.argumentName() == null || token.isEmpty()) {
            return null;
        }
        ArgumentType type = node.argumentType();
        try {
            return switch (type) {
                case WORD, STRING -> new Parsed(token, index + 1);
                case GREEDY_STRING -> new Parsed(String.join(" ", Arrays.copyOfRange(args, index, args.length)), args.length);
                case INTEGER -> new Parsed(Integer.parseInt(token), index + 1);
                case BOOLEAN -> "true".equalsIgnoreCase(token) || "false".equalsIgnoreCase(token)
                        ? new Parsed(Boolean.parseBoolean(token), index + 1) : null;
                case PLAYER -> {
                    IPlayer player = platform.getPlayerByName(token);
                    yield player == null ? null : new Parsed(player, index + 1);
                }
            };
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private void suggest(ForgeCommandBuilder node, String[] args, int index,
            Map<String, Object> values, ICommandSource source, String input, Set<String> result) {
        if (index == args.length - 1) {
            String partial = args[index];
            for (ForgeCommandBuilder child : node.children()) {
                if (!allowed(child, source)) {
                    continue;
                }
                List<String> candidates;
                if (child.literalName() != null) {
                    candidates = List.of(child.literalName());
                } else if (child.suggestions() != null) {
                    candidates = child.suggestions().getSuggestions(
                            new ForgeCommandContext(source, values, input), partial);
                } else if (child.argumentType() == ArgumentType.PLAYER) {
                    candidates = platform.getOnlinePlayerNames();
                } else if (child.argumentType() == ArgumentType.BOOLEAN) {
                    candidates = List.of("true", "false");
                } else {
                    candidates = List.of();
                }
                addMatching(result, candidates, partial);
            }
            return;
        }
        for (ForgeCommandBuilder child : node.children()) {
            if (!allowed(child, source)) {
                continue;
            }
            if (child.argumentType() == ArgumentType.GREEDY_STRING
                    && child.suggestions() != null) {
                String remaining = String.join(" ", Arrays.copyOfRange(args, index, args.length));
                addMatching(result, child.suggestions().getSuggestions(
                        new ForgeCommandContext(source, values, input), remaining), remaining);
                continue;
            }
            Parsed parsed = parse(child, args, index);
            if (parsed == null || parsed.nextIndex > args.length - 1) {
                continue;
            }
            Map<String, Object> next = new LinkedHashMap<>(values);
            if (child.argumentName() != null) {
                next.put(child.argumentName(), parsed.value);
            }
            suggest(child, args, parsed.nextIndex, next, source, input, result);
        }
    }

    private static void addMatching(Set<String> result, List<String> candidates, String partial) {
        if (candidates == null) {
            return;
        }
        for (String candidate : candidates) {
            if (candidate != null && candidate.toLowerCase(Locale.ROOT)
                    .startsWith(partial.toLowerCase(Locale.ROOT))) {
                result.add(candidate);
            }
        }
    }

    private static boolean allowed(ForgeCommandBuilder node, ICommandSource source) {
        try {
            return node.requirement().test(source);
        } catch (RuntimeException failure) {
            LOGGER.error("Paradigm command requirement failed", failure);
            return false;
        }
    }

    private record Parsed(Object value, int nextIndex) {}

    private record Match(ForgeCommandBuilder node, Map<String, Object> arguments, int score) {}

    private record Contribution(Object owner, ForgeCommandBuilder root) {}
}
