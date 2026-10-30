package eu.avalanche7.paradigm.modules.commands.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public class InventoryInspectCommand extends AbstractAdminCommand {
    private static final int MAX_LINES = 20;
    private record Inspection(UUID target, boolean ender, IMenuPlatform.Handle handle, MainConfigHandler.Config config) {}
    private final Map<UUID, Inspection> inspections = new LinkedHashMap<>();
    private ScheduledFuture<?> refreshTask;
    private volatile boolean active;

    @Override public void onEnable(Services services) { this.services = services; active = true; }
    @Override public void onDisable(Services services) { active = false; closeAll(); }
    @Override public void onServerStopping(Object event, Services services) { onDisable(services); }
    @Override public void onServerStarting(Object event, Services services) { closeAll(); }

    @Override public void registerEventListeners(Object eventBus, Services services) {
        if (lifecycleEvents(services) != null) lifecycleEvents(services).onPlayerLeave(event -> {
            if (event.getPlayer() == null) return;
            UUID departed = UUID.fromString(event.getPlayer().getUUID());
            for (var entry : List.copyOf(inspections.entrySet()))
                if (entry.getKey().equals(departed) || entry.getValue().target().equals(departed)) close(entry.getKey(), entry.getValue());
        });
    }

    private void close(UUID viewer, Inspection inspection) {
        if (inspections.remove(viewer, inspection)) inspection.handle().close();
        if (inspections.isEmpty() && refreshTask != null) { refreshTask.cancel(false); refreshTask = null; }
    }

    private void closeAll() {
        services.getPlatformAdapter().executeOnServerThread(() -> {
            for (var entry : List.copyOf(inspections.entrySet())) close(entry.getKey(), entry.getValue());
        });
    }

    void refreshInspections() {
        var platform = services.getPlatformAdapter();
        for (var entry : List.copyOf(inspections.entrySet())) {
            var inspection = entry.getValue();
            try {
                var viewer = platform.getPlayerByUuid(entry.getKey().toString());
                var target = platform.getPlayerByUuid(inspection.target().toString());
                if (!active || inspection.config() != services.getMainConfig() || !isEnabled(services) || viewer == null || target == null
                        || !services.getCommandAccess().isCommandEnabled(inspection.ender() ? "endersee" : "invsee")
                        || !services.getCommandAccess().allows(viewer, inspection.ender() ? ParadigmPermissions.ENDER_SEE : ParadigmPermissions.INVENTORY_SEE)
                        || !platform.canSeePlayer(viewer, target) || !inspection.handle().isOpen()
                        || !inspection.handle().refreshInventory(target, inspection.ender())) close(entry.getKey(), inspection);
            } catch (RuntimeException failure) {
                close(entry.getKey(), inspection);
                services.getLogger().warn("Paradigm: inventory inspection refresh failed for {}", entry.getKey(), failure);
            }
        }
    }

    private int open(ICommandSource source, IPlayer viewer, IPlayer target, boolean ender) {
        var platform = services.getPlatformAdapter();
        if (!active || !isEnabled(services) || !allowed(source, ender ? "endersee" : "invsee", ender ? ParadigmPermissions.ENDER_SEE : ParadigmPermissions.INVENTORY_SEE)
                || !platform.canSeePlayer(viewer, target)) return 0;
        UUID viewerId = UUID.fromString(viewer.getUUID());
        var previous = inspections.get(viewerId);
        if (previous != null) close(viewerId, previous);
        var menus = platform.getMenuPlatform();
        if (menus == null) return 0;
        Map<Integer, IMenuPlatform.ItemSpec> decoration = new LinkedHashMap<>();
        if (!ender) {
            for (int slot = 9; slot < 18; slot++) decoration.put(slot, new IMenuPlatform.ItemSpec("minecraft:paper", 1,
                    platform.createLiteralComponent("Equipment above | Inventory below | Hotbar last row"), List.of(), false, null, false));
        }
        Inspection[] created = new Inspection[1];
        var handle = menus.open(viewer, platform.createLiteralComponent((ender ? "Ender Chest: " : "Inventory: ") + target.getName()), ender ? 27 : 54, decoration,
                new IMenuPlatform.ClickListener() {
                    public void onSlotActivated(IPlayer player, int slot, IMenuPlatform.ClickKind kind) {}
                    public void onClosed(IPlayer player) { if (created[0] != null) close(viewerId, created[0]); }
                });
        if (handle == null) return 0;
        try {
            if (!handle.isOpen() || !handle.refreshInventory(target, ender)) { handle.close(); return 0; }
        } catch (RuntimeException failure) { handle.close(); throw failure; }
        var inspection = new Inspection(UUID.fromString(target.getUUID()), ender, handle, services.getMainConfig());
        created[0] = inspection;
        inspections.put(viewerId, inspection);
        if (refreshTask == null) refreshTask = services.getTaskScheduler().scheduleAtFixedRate(
                () -> platform.executeOnServerThread(this::refreshInspections), 1, 1, TimeUnit.SECONDS);
        if (refreshTask.isCancelled() || refreshTask.isDone()) { close(viewerId, inspection); return 0; }
        return 1;
    }

    @Override
    public String getName() {
        return "InventoryInspect";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        registerInventory();
        registerEnder();
    }

    private void registerInventory() {
        ICommandBuilder cmd = builder()
                .literal("invsee")
                .requires(src -> allowed(src, "invsee", ParadigmPermissions.INVENTORY_SEE))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> inspect(ctx.getSource(), ctx.getPlayerArgument("player"), false)));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private void registerEnder() {
        ICommandBuilder cmd = builder()
                .literal("endersee")
                .requires(src -> allowed(src, "endersee", ParadigmPermissions.ENDER_SEE))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> inspect(ctx.getSource(), ctx.getPlayerArgument("player"), true)));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int inspect(ICommandSource source, IPlayer target, boolean ender) {
        if (target == null) {
            send(source, "admin.player_not_found", "Player not found.");
            return 0;
        }
        if (source.getPlayer() != null) return open(source, source.getPlayer(), target, ender);
        var stacks = services.getPlatformAdapter().inspectPlayerInventory(target, ender);
        send(source, "admin.inspect_header", "{type} for {player}:", "{type}", ender ? "Ender chest" : "Inventory", "{player}", target.getName());
        int shown = 0;
        for (var stack : stacks) {
            shown++;
            if (shown <= MAX_LINES) {
                send(source, "admin.inspect_line", "#{slot}: {count}x {item}",
                        "{slot}", String.valueOf(stack.slot()),
                        "{count}", String.valueOf(stack.count()),
                        "{item}", stack.displayName());
            }
        }
        if (shown == 0) {
            send(source, "admin.inspect_empty", "No items found.");
        } else if (shown > MAX_LINES) {
            send(source, "admin.inspect_more", "...and {count} more item stacks.", "{count}", String.valueOf(shown - MAX_LINES));
        }
        return 1;
    }
}
