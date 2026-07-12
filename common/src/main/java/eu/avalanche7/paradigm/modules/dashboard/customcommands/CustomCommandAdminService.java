package eu.avalanche7.paradigm.modules.dashboard.customcommands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.modules.CommandManager;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Constrained CRUD service for the custom-command directory. */
public final class CustomCommandAdminService {
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_*.-]{1,128}");
    private static final Set<String> ACTIONS = Set.of("message", "teleport", "run_command", "runcmd", "command", "run_console", "conditional");
    private static final Set<String> CONDITIONS = Set.of("has_permission", "has_item", "health_above", "health_below", "is_op");
    private static final String MANAGED_FILE = "dashboard-commands.json";

    private final Services services;
    private final Path directory;
    private final Supplier<Integer> reloader;

    public CustomCommandAdminService(Services services) {
        this(services,
                services.getPlatformAdapter().getConfig().getConfigDirectory().resolve("paradigm").resolve("commands"),
                () -> CommandManager.reloadCustomCommands(services));
    }

    CustomCommandAdminService(Path directory, Supplier<Integer> reloader) {
        this(null, directory, reloader);
    }

    private CustomCommandAdminService(Services services, Path directory, Supplier<Integer> reloader) {
        this.services = services;
        this.directory = directory;
        this.reloader = reloader;
    }

    public List<CommandView> list(String query) {
        String q = text(query).toLowerCase(Locale.ROOT);
        List<CommandView> result = new ArrayList<>();
        for (Document document : documents()) {
            for (JsonElement element : document.commands()) {
                if (!element.isJsonObject()) continue;
                JsonObject command = element.getAsJsonObject();
                String name = string(command, "name");
                if (!q.isBlank() && !name.toLowerCase(Locale.ROOT).contains(q)
                        && !string(command, "description").toLowerCase(Locale.ROOT).contains(q)) continue;
                result.add(view(command, document.path()));
            }
        }
        result.sort(Comparator.comparing(CommandView::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public CommandView get(String name) {
        Located located = locate(name);
        return located != null ? view(located.command(), located.path()) : null;
    }

    public MutationResult create(JsonObject command) {
        JsonObject normalized = validate(command, null);
        String name = string(normalized, "name");
        if (locate(name) != null) throw new IllegalArgumentException("A custom command with that name already exists.");
        Path path = directory.resolve(MANAGED_FILE);
        JsonArray commands = readArray(path);
        commands.add(normalized);
        writeAtomic(path, commands);
        return reloaded("created", name);
    }

    public MutationResult update(String originalName, JsonObject command) {
        Located located = requireLocated(originalName);
        JsonObject normalized = validate(command, originalName);
        String newName = string(normalized, "name");
        Located collision = locate(newName);
        if (collision != null && !sameLocation(located, collision)) {
            throw new IllegalArgumentException("A custom command with that name already exists.");
        }
        located.commands().set(located.index(), normalized);
        writeAtomic(located.path(), located.commands());
        return reloaded(originalName.equalsIgnoreCase(newName) ? "updated" : "renamed", newName);
    }

    public MutationResult duplicate(String sourceName, String requestedName) {
        Located source = requireLocated(sourceName);
        JsonObject copy = source.command().deepCopy();
        copy.addProperty("name", text(requestedName));
        return create(copy);
    }

    public MutationResult delete(String name) {
        Located located = requireLocated(name);
        located.commands().remove(located.index());
        writeAtomic(located.path(), located.commands());
        return reloaded("deleted", string(located.command(), "name"));
    }

    public MutationResult reload() {
        return new MutationResult("reloaded", "", reloadCommands().join());
    }

    private MutationResult reloaded(String action, String name) {
        return new MutationResult(action, name, reloadCommands().join());
    }

    private CompletableFuture<Integer> reloadCommands() {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                result.complete(reloader.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        };
        if (services != null && services.getTaskScheduler() != null) {
            services.getTaskScheduler().schedule(task, 0L, TimeUnit.MILLISECONDS);
        } else {
            task.run();
        }
        return result;
    }

    private JsonObject validate(JsonObject input, String originalName) {
        if (input == null) throw new IllegalArgumentException("Command data is required.");
        JsonObject command = input.deepCopy();
        String name = text(string(command, "name")).toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Command name must use 1-32 lowercase letters, numbers, underscores, or dashes.");
        command.addProperty("name", name);
        String permission = text(string(command, "permission"));
        if (!permission.isBlank() && !PERMISSION.matcher(permission).matches()) throw new IllegalArgumentException("Permission node is invalid.");
        JsonArray actions = array(command, "actions");
        if (actions.isEmpty()) throw new IllegalArgumentException("Add at least one command action.");
        validateActions(actions, 0);
        validateArguments(array(command, "arguments"));
        if (command.has("cooldown_seconds") && !command.get("cooldown_seconds").isJsonNull()
                && command.get("cooldown_seconds").getAsInt() < 0) throw new IllegalArgumentException("Cooldown cannot be negative.");
        validateArea(command.getAsJsonObject("area_restriction"));
        return command;
    }

    private void validateActions(JsonArray actions, int depth) {
        if (depth > 6) throw new IllegalArgumentException("Conditional action nesting is too deep.");
        for (JsonElement element : actions) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Every action must be an object.");
            JsonObject action = element.getAsJsonObject();
            String type = string(action, "type").toLowerCase(Locale.ROOT);
            if (!ACTIONS.contains(type)) throw new IllegalArgumentException("Unsupported action type: " + type);
            if ("message".equals(type) && array(action, "text").isEmpty()) throw new IllegalArgumentException("Message actions require text.");
            if (("run_command".equals(type) || "runcmd".equals(type) || "command".equals(type) || "run_console".equals(type))
                    && array(action, "commands").isEmpty()) throw new IllegalArgumentException("Command actions require at least one command.");
            if ("teleport".equals(type) && (!action.has("x") || !action.has("y") || !action.has("z"))) throw new IllegalArgumentException("Teleport actions require x, y, and z.");
            for (JsonElement conditionElement : array(action, "conditions")) {
                if (!conditionElement.isJsonObject()) throw new IllegalArgumentException("Every condition must be an object.");
                String condition = string(conditionElement.getAsJsonObject(), "type").toLowerCase(Locale.ROOT);
                if (!CONDITIONS.contains(condition)) throw new IllegalArgumentException("Unsupported condition type: " + condition);
            }
            validateActions(array(action, "on_success"), depth + 1);
            validateActions(array(action, "on_failure"), depth + 1);
        }
    }

    private void validateArguments(JsonArray arguments) {
        Set<String> names = new HashSet<>();
        for (JsonElement element : arguments) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Every argument must be an object.");
            JsonObject argument = element.getAsJsonObject();
            String name = text(string(argument, "name"));
            if (!NAME.matcher(name).matches() || !names.add(name)) throw new IllegalArgumentException("Argument names must be valid and unique.");
            String type = string(argument, "type").toLowerCase(Locale.ROOT);
            if (!Set.of("string", "integer", "boolean", "player", "world", "gamemode", "custom").contains(type)) {
                throw new IllegalArgumentException("Unsupported argument type: " + type);
            }
        }
    }

    private void validateArea(JsonObject area) {
        if (area == null) return;
        if (string(area, "world").isBlank() || array(area, "corner1").size() != 3 || array(area, "corner2").size() != 3) {
            throw new IllegalArgumentException("Area restrictions require a world and two three-coordinate corners.");
        }
    }

    private List<Document> documents() {
        List<Document> documents = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            try (var paths = Files.list(directory)) {
                paths.filter(this::safeJsonFile).sorted().forEach(path -> documents.add(new Document(path, readArray(path))));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not read custom commands.", e);
        }
        return documents;
    }

    private Located locate(String rawName) {
        String name = text(rawName);
        for (Document document : documents()) {
            for (int i = 0; i < document.commands().size(); i++) {
                JsonElement element = document.commands().get(i);
                if (element.isJsonObject() && name.equalsIgnoreCase(string(element.getAsJsonObject(), "name"))) {
                    return new Located(document.path(), document.commands(), i, element.getAsJsonObject());
                }
            }
        }
        return null;
    }

    private Located requireLocated(String name) {
        Located located = locate(name);
        if (located == null) throw new IllegalArgumentException("Custom command was not found.");
        return located;
    }

    private boolean sameLocation(Located first, Located second) {
        return first.path().equals(second.path()) && first.index() == second.index();
    }

    private boolean safeJsonFile(Path path) {
        return path != null && path.getParent().equals(directory) && Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private JsonArray readArray(Path path) {
        if (!Files.exists(path)) return new JsonArray();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonArray()) throw new IllegalArgumentException("Custom command file must contain a JSON array: " + path.getFileName());
            return parsed.getAsJsonArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + path.getFileName() + ".", e);
        }
    }

    private void writeAtomic(Path path, JsonArray commands) {
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, ".paradigm-commands-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                DashboardJson.GSON.toJson(commands, writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not save custom commands.", e);
        }
    }

    private CommandView view(JsonObject command, Path path) {
        CustomCommand parsed = DashboardJson.GSON.fromJson(command, CustomCommand.class);
        return new CommandView(string(command, "name"), string(command, "description"), string(command, "permission"),
                parsed != null && parsed.isRequirePermission(), parsed != null ? parsed.getCooldownSeconds() : null,
                parsed != null && parsed.getActions() != null ? parsed.getActions().size() : 0,
                parsed != null ? parsed.getArguments().size() : 0, path.getFileName().toString(), command.deepCopy());
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object != null ? object.get(key) : null;
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object != null ? object.get(key) : null;
        return element != null && !element.isJsonNull() ? element.getAsString() : "";
    }

    private static String text(String value) {
        return value != null ? value.trim() : "";
    }

    private record Document(Path path, JsonArray commands) {}
    private record Located(Path path, JsonArray commands, int index, JsonObject command) {}
    public record CommandView(String name, String description, String permission, boolean requirePermission,
                              Integer cooldownSeconds, int actionCount, int argumentCount, String sourceFile, JsonObject command) {}
    public record MutationResult(String action, String name, int loadedCommands) {}
}
