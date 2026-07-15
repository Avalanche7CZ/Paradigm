package eu.avalanche7.paradigm.modules.holograms;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.util.List;

public final class Holograms implements ParadigmModule {
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
        return root;
    }

    private ICommandBuilder idArgument() {
        return builder().argument("id", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> holograms.definitions().keySet().stream().sorted().toList());
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
