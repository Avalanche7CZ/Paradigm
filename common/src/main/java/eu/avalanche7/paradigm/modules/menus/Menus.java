package eu.avalanche7.paradigm.modules.menus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class Menus implements ParadigmModule {

    public static final String DIRECTORY = "paradigm/menus";

    private static volatile Menus current;

    private Services services;
    private MenuStore store;
    private MenuService menuService;
    private final List<String> lastErrors = new ArrayList<>();

    @Override
    public String getName() {
        return "Menus";
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.store = new MenuStore(this::directory);
        this.menuService = services.getMenuService();
        current = this;
        reload();
    }

    public static Menus current() {
        return current;
    }

    public MenuStore store() {
        return store;
    }

    public MenuService service() {
        return menuService;
    }

    public List<String> lastErrors() {
        return List.copyOf(lastErrors);
    }

    public List<String> reload() {
        List<String> problems = new ArrayList<>();
        if (store == null || menuService == null) {
            return problems;
        }
        MenuStore.LoadResult result = store.loadAll();
        problems.addAll(result.errors());
        menuService.registry().replaceConfigDefinitions(result.definitions());
        problems.addAll(menuService.validateReferences());
        menuService.onReload();

        lastErrors.clear();
        lastErrors.addAll(problems);
        if (!problems.isEmpty() && services.getLogger() != null) {
            for (String problem : problems) {
                services.getLogger().warn("Paradigm menus: {}", problem);
            }
        }
        return problems;
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        reload();
    }

    @Override
    public void onEnable(Services services) {
        if (menuService != null) {
            menuService.setActive(true);
        }
    }

    @Override
    public void onDisable(Services services) {
        if (menuService != null) {
            menuService.setActive(false);
        }
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        if (menuService != null) {
            menuService.closeAll();
        }
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        if (lifecycleEvents(services) == null) {
            return;
        }
        lifecycleEvents(services).onPlayerLeave(event -> {
            IPlayer player = event.getPlayer();
            if (player == null || player.getUUID() == null) {
                return;
            }
            try {
                menuService.handleDisconnect(java.util.UUID.fromString(player.getUUID()));
            } catch (IllegalArgumentException ignored) {
            }
        });
    }

    public ICommandBuilder buildCommandBranch() {
        ICommandBuilder root = builder().literal("menu").requires(this::canManage);

        root.then(builder().literal("list").executes(context -> list(context.getSource())));
        root.then(builder().literal("reload").executes(context -> reloadCommand(context.getSource())));
        root.then(builder().literal("info")
                .then(menuArgument().executes(context -> info(context.getSource(), context.getStringArgument("menu")))));
        root.then(builder().literal("open")
                .then(menuArgument()
                        .executes(context -> open(context.getSource(), context.getStringArgument("menu"), null))
                        .then(builder().argument("player", ICommandBuilder.ArgumentType.PLAYER)
                                .executes(context -> open(context.getSource(), context.getStringArgument("menu"),
                                        context.getPlayerArgument("player"))))));
        root.then(builder().literal("close")
                .then(builder().argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(context -> close(context.getSource(), context.getPlayerArgument("player")))));
        root.then(builder().literal("sessions").executes(context -> sessions(context.getSource())));
        return root;
    }

    private int list(ICommandSource source) {
        List<MenuDefinition> definitions = menuService.registry().all();
        if (definitions.isEmpty()) {
            success(source, "&eNo menus are defined.");
            return 1;
        }
        success(source, "&aMenus (" + definitions.size() + "):");
        for (MenuDefinition definition : definitions) {
            MenuRegistry.Entry entry = menuService.registry().entry(definition.id);
            String origin = entry != null && entry.origin() == MenuRegistry.Origin.PROGRAMMATIC ? "module" : "config";
            success(source, "&7- &f" + definition.id + " &7(" + definition.rows + " rows, " + origin
                    + (definition.permission.isBlank() ? "" : ", " + definition.permission) + ")");
        }
        return 1;
    }

    private int info(ICommandSource source, String id) {
        MenuDefinition definition = menuService.registry().get(id);
        if (definition == null) {
            failure(source, "&cUnknown menu '" + id + "'.");
            return 0;
        }
        success(source, "&aMenu &f" + definition.id);
        success(source, "&7Title: &f" + definition.title);
        success(source, "&7Rows: &f" + definition.rows + " &7(" + definition.size() + " slots)");
        success(source, "&7Permission: &f" + (definition.permission.isBlank() ? "none" : definition.permission));
        success(source, "&7Defined slots: &f" + definition.slots.size());
        success(source, "&7Refresh: &f" + (definition.isDynamic()
                ? (definition.refreshSeconds > 0 ? definition.refreshSeconds + "s" : "5s") : "static"));
        return 1;
    }

    private int reloadCommand(ICommandSource source) {
        List<String> problems = reload();
        if (problems.isEmpty()) {
            success(source, "&aReloaded " + menuService.registry().size() + " menu(s).");
            return 1;
        }
        failure(source, "&eReloaded with " + problems.size() + " problem(s):");
        for (String problem : problems) {
            failure(source, "&7- &c" + problem);
        }
        return 1;
    }

    private int open(ICommandSource source, String id, IPlayer target) {
        IPlayer player = target != null ? target : (source != null ? source.getPlayer() : null);
        if (player == null) {
            failure(source, "&cSpecify a player when running this from the console.");
            return 0;
        }
        if (target != null && source != null && !source.isConsole()
                && source.getPlayer() != null && !samePlayer(source.getPlayer(), target)
                && !services.getPermissionsHandler().hasPermission(source.getPlayer(),
                        ParadigmPermissions.MENU_OPEN_OTHERS)) {
            failure(source, "&cYou may not open menus for other players.");
            return 0;
        }
        MenuService.OpenResult result = menuService.open(player, id, null, false);
        switch (result) {
            case OPENED -> success(source, "&aOpened '" + id + "' for " + player.getName() + ".");
            case UNKNOWN_MENU -> failure(source, "&cUnknown menu '" + id + "'.");
            case NO_PERMISSION -> failure(source, "&c" + player.getName() + " lacks permission for that menu.");
            case CONDITIONS_FAILED -> failure(source, "&cOpen conditions failed for " + player.getName() + ".");
            case UNSUPPORTED_PLATFORM -> failure(source, "&cMenus are not supported on this server build.");
            default -> failure(source, "&cFailed to open '" + id + "'.");
        }
        return result == MenuService.OpenResult.OPENED ? 1 : 0;
    }

    private int close(ICommandSource source, IPlayer target) {
        if (target == null) {
            failure(source, "&cUnknown player.");
            return 0;
        }
        menuService.close(target);
        success(source, "&aClosed any Paradigm menu for " + target.getName() + ".");
        return 1;
    }

    private int sessions(ICommandSource source) {
        var open = menuService.sessions();
        if (open.isEmpty()) {
            success(source, "&eNo players currently have a Paradigm menu open.");
            return 1;
        }
        success(source, "&aOpen menu sessions (" + open.size() + "):");
        for (MenuSession session : open) {
            success(source, "&7- &f" + session.viewerName() + " &7→ &f" + session.menuId()
                    + " &7(history " + session.historySnapshot().size() + ")");
        }
        return 1;
    }

    private static boolean samePlayer(IPlayer first, IPlayer second) {
        return first != null && second != null && first.getUUID() != null && first.getUUID().equals(second.getUUID());
    }

    private ICommandBuilder menuArgument() {
        return builder().argument("menu", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> new ArrayList<>(menuService.registry().ids()));
    }

    private ICommandBuilder builder() {
        return services.getPlatformAdapter().createCommandBuilder();
    }

    private boolean canManage(ICommandSource source) {
        return source == null || source.isConsole()
                || services.getPermissionsHandler().hasPermission(source.getPlayer(),
                        ParadigmPermissions.MENU_MANAGE);
    }

    private Path directory() {
        return services.getPlatformAdapter().getConfig().resolveConfigPath(DIRECTORY);
    }

    private void success(ICommandSource source, String message) {
        IComponent component = services.getMessageParser().parseMessage(message, null);
        services.getPlatformAdapter().sendSuccess(source, component, false);
    }

    private void failure(ICommandSource source, String message) {
        IComponent component = services.getMessageParser().parseMessage(message, null);
        services.getPlatformAdapter().sendFailure(source, component);
    }
}
