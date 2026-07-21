package eu.avalanche7.paradigm.modules.holograms;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.util.List;

public final class Holograms implements ParadigmModule {
    private static volatile Holograms current;

    private Services services;
    private HologramService holograms;

    @Override
    public String getName() {
        return "Holograms";
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.holograms = services.getHologramService();
        current = this;
    }

    public static Holograms current() {
        return current;
    }

    public ICommandBuilder buildCommandBranch() {
        return command("hologram");
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        holograms.start();
    }

    @Override
    public void onEnable(Services services) {
        holograms.start();
    }

    @Override
    public void onDisable(Services services) {
        holograms.stop();
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        holograms.stop();
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        services.getPlatformAdapter().registerCommand(command("hologram"));
        services.getPlatformAdapter().registerCommand(command("holo"));
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private ICommandBuilder command(String rootName) {
        ICommandBuilder root = builder().literal(rootName).requires(this::canManage);
        root.then(builder().literal("create")
                .then(builder().argument("id", ICommandBuilder.ArgumentType.WORD)
                        .executes(ctx -> action(ctx.getSource(), () -> create(ctx.getSource(), ctx.getStringArgument("id"))))));
        root.then(builder().literal("delete")
                .then(idArgument().executes(ctx -> action(ctx.getSource(), () -> {
                    holograms.delete(ctx.getStringArgument("id"));
                    success(ctx.getSource(), "Deleted hologram '" + ctx.getStringArgument("id") + "'.");
                }))));
        root.then(builder().literal("list").executes(ctx -> list(ctx.getSource())));
        root.then(builder().literal("info").then(idArgument().executes(ctx -> info(ctx.getSource(), ctx.getStringArgument("id")))));
        root.then(builder().literal("addline").then(idArgument()
                .then(builder().argument("text", ICommandBuilder.ArgumentType.GREEDY_STRING)
                        .executes(ctx -> action(ctx.getSource(), () -> {
                            holograms.addLine(ctx.getStringArgument("id"), ctx.getStringArgument("text"));
                            success(ctx.getSource(), "Line added.");
                        })))));
        root.then(builder().literal("setline").then(idArgument()
                .then(builder().argument("line", ICommandBuilder.ArgumentType.INTEGER)
                        .then(builder().argument("text", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> action(ctx.getSource(), () -> {
                                    holograms.setLine(ctx.getStringArgument("id"), ctx.getIntArgument("line"), ctx.getStringArgument("text"));
                                    success(ctx.getSource(), "Line updated.");
                                }))))));
        root.then(builder().literal("removeline").then(idArgument()
                .then(builder().argument("line", ICommandBuilder.ArgumentType.INTEGER)
                        .executes(ctx -> action(ctx.getSource(), () -> {
                            holograms.removeLine(ctx.getStringArgument("id"), ctx.getIntArgument("line"));
                            success(ctx.getSource(), "Line removed.");
                        })))));
        root.then(builder().literal("movehere").then(idArgument()
                .executes(ctx -> action(ctx.getSource(), () -> moveHere(ctx.getSource(), ctx.getStringArgument("id"))))));
        root.then(builder().literal("teleport").then(idArgument()
                .executes(ctx -> action(ctx.getSource(), () -> teleport(ctx.getSource(), ctx.getStringArgument("id"))))));
        root.then(builder().literal("refresh")
                .executes(ctx -> action(ctx.getSource(), () -> {
                    holograms.refresh(null);
                    success(ctx.getSource(), "All holograms refreshed.");
                }))
                .then(idArgument().executes(ctx -> action(ctx.getSource(), () -> {
                    holograms.refresh(ctx.getStringArgument("id"));
                    success(ctx.getSource(), "Hologram refreshed.");
                }))));
        ICommandBuilder temporary = builder().literal("temporary");
        temporary.then(builder().literal("list").executes(ctx -> listTemporary(ctx.getSource())));
        temporary.then(builder().literal("info").then(temporaryIdArgument().executes(ctx -> temporaryInfo(ctx.getSource(), ctx.getStringArgument("id")))));
        temporary.then(builder().literal("remove").then(temporaryIdArgument().executes(ctx -> action(ctx.getSource(), () -> {
            if (!holograms.removeTemporary(ctx.getStringArgument("id"))) throw new IllegalArgumentException("Unknown temporary hologram.");
            success(ctx.getSource(), "Temporary hologram removed.");
        }))));
        temporary.then(builder().literal("create")
                .then(builder().argument("ttlSeconds", ICommandBuilder.ArgumentType.INTEGER)
                        .then(builder().argument("text", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> action(ctx.getSource(), () -> createTemporary(ctx.getSource(), ctx.getIntArgument("ttlSeconds"), ctx.getStringArgument("text")))))));
        root.then(temporary);
        return root;
    }

    private ICommandBuilder idArgument() {
        return builder().argument("id", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> holograms.definitions().keySet().stream().sorted().toList());
    }

    private ICommandBuilder temporaryIdArgument() {
        return builder().argument("id", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> holograms.temporaryHolograms().stream().map(value -> value.id).sorted().toList());
    }

    private ICommandBuilder builder() {
        return services.getPlatformAdapter().createCommandBuilder();
    }

    private boolean canManage(ICommandSource source) {
        return services.getCommandToggleStore().isEnabled("hologram")
                && (source == null || source.isConsole() || services.getPermissionsHandler().hasPermission(
                source.getPlayer(), HologramService.MANAGE_PERMISSION, HologramService.MANAGE_PERMISSION_LEVEL));
    }

    private void create(ICommandSource source, String id) {
        IPlayer player = requirePlayer(source);
        holograms.create(id, player.getWorldId(), required(player.getX(), "x"), required(player.getY(), "y"), required(player.getZ(), "z"));
        success(source, "Created hologram '" + id + "' at your location.");
    }

    private void createTemporary(ICommandSource source, int ttlSeconds, String text) {
        if (ttlSeconds < 1 || ttlSeconds > 86400) throw new IllegalArgumentException("TTL must be between 1 and 86400 seconds.");
        IPlayer player = requirePlayer(source);
        HologramDefinition definition = new HologramDefinition();
        definition.dimension = player.getWorldId();
        definition.x = required(player.getX(), "x");
        definition.y = required(player.getY(), "y") + 0.5D;
        definition.z = required(player.getZ(), "z");
        definition.lines.clear();
        definition.lines.add(text);
        TemporaryHologram temporary = holograms.createTemporary(definition, player.getUUID(), (long) ttlSeconds, null);
        success(source, "Temporary hologram created: " + temporary.id + ".");
    }

    private void moveHere(ICommandSource source, String id) {
        IPlayer player = requirePlayer(source);
        holograms.move(id, player.getWorldId(), required(player.getX(), "x"), required(player.getY(), "y"), required(player.getZ(), "z"));
        success(source, "Moved hologram '" + id + "' to your location.");
    }

    private void teleport(ICommandSource source, String id) {
        IPlayer player = requirePlayer(source);
        HologramDefinition definition = holograms.definition(id);
        if (definition == null) throw new IllegalArgumentException("Unknown hologram: " + id);
        boolean teleported = services.getPlatformAdapter().teleportPlayer(player,
                new PlayerDataStore.StoredLocation(definition.dimension, definition.x, definition.y, definition.z,
                        player.getYaw() != null ? player.getYaw() : 0.0F, player.getPitch() != null ? player.getPitch() : 0.0F));
        if (!teleported) throw new IllegalStateException("Could not teleport to that hologram.");
        success(source, "Teleported to hologram '" + id + "'.");
    }

    private int list(ICommandSource source) {
        List<String> ids = holograms.definitions().keySet().stream().sorted().toList();
        success(source, ids.isEmpty() ? "No holograms configured." : "Holograms: " + String.join(", ", ids));
        return 1;
    }

    private int listTemporary(ICommandSource source) {
        List<TemporaryHologram> values = holograms.temporaryHolograms();
        success(source, values.isEmpty() ? "No temporary holograms." : "Temporary holograms: " + String.join(", ", values.stream().map(value -> value.id).toList()));
        return 1;
    }

    private int temporaryInfo(ICommandSource source, String id) {
        TemporaryHologram value = holograms.temporary().get(id);
        if (value == null) {
            failure(source, "Unknown temporary hologram.");
            return 0;
        }
        success(source, value.id + " · " + value.definition.dimension + " · " + value.definition.lines.size() + " lines · "
                + (value.expiresAt != null ? "expires " + value.expiresAt : "no expiry"));
        return 1;
    }

    private int info(ICommandSource source, String id) {
        HologramDefinition definition = holograms.definition(id);
        if (definition == null) {
            failure(source, "Unknown hologram: " + id);
            return 0;
        }
        success(source, id + " · " + definition.dimension + " · " + format(definition.x) + ", "
                + format(definition.y) + ", " + format(definition.z) + " · " + definition.lines.size() + " lines · "
                + (definition.enabled ? "enabled" : "disabled"));
        return 1;
    }

    private int action(ICommandSource source, Runnable action) {
        try {
            action.run();
            return 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            failure(source, e.getMessage());
            return 0;
        }
    }

    private IPlayer requirePlayer(ICommandSource source) {
        if (source == null || source.getPlayer() == null) throw new IllegalStateException("This command requires a player location.");
        return source.getPlayer();
    }

    private static double required(Double value, String coordinate) {
        if (value == null) throw new IllegalStateException("Player " + coordinate + " coordinate is unavailable.");
        return value;
    }

    private void success(ICommandSource source, String message) {
        services.getPlatformAdapter().sendSuccess(source,
                services.getMessageParser().parseMessage("<color:#22D3EE><bold>[Holograms]</bold></color> <color:#E5E7EB>" + message + "</color>", source != null ? source.getPlayer() : null), false);
    }

    private void failure(ICommandSource source, String message) {
        services.getPlatformAdapter().sendFailure(source,
                services.getMessageParser().parseMessage("<color:red>" + (message != null ? message : "Hologram operation failed.") + "</color>", source != null ? source.getPlayer() : null));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
