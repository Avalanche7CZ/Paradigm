package eu.avalanche7.paradigm.modules.menus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.modules.actions.ActionContext;
import eu.avalanche7.paradigm.modules.actions.ActionDispatcher;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class MenuService {

    public enum OpenResult {
        OPENED,
        UNKNOWN_MENU,
        NO_PERMISSION,
        CONDITIONS_FAILED,
        UNSUPPORTED_PLATFORM,
        NO_PLAYER,
        FAILED
    }

    private final Services services;
    private final MenuRegistry registry;
    private final ActionDispatcher dispatcher;
    private final MenuRenderer renderer;
    private final Map<UUID, MenuSession> sessions = new ConcurrentHashMap<>();

    private volatile boolean active = true;

    public MenuService(Services services, MenuRegistry registry, ActionDispatcher dispatcher) {
        this.services = services;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.renderer = new MenuRenderer(services, dispatcher);
    }

    public MenuRegistry registry() {
        return registry;
    }

    public ActionDispatcher dispatcher() {
        return dispatcher;
    }

    public MenuRenderer renderer() {
        return renderer;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean value) {
        this.active = value;
        if (!value) {
            closeAll();
        }
    }

    public int openSessionCount() {
        return sessions.size();
    }

    @Nullable
    public MenuSession session(UUID viewer) {
        return viewer != null ? sessions.get(viewer) : null;
    }

    public Collection<MenuSession> sessions() {
        return List.copyOf(sessions.values());
    }

    public OpenResult open(IPlayer player, String menuId, @Nullable Map<String, String> contextValues) {
        return open(player, menuId, contextValues, true);
    }

    public OpenResult open(IPlayer player, String menuId, @Nullable Map<String, String> contextValues, boolean enforceAccess) {
        if (!active) {
            return OpenResult.FAILED;
        }
        if (player == null) {
            return OpenResult.NO_PLAYER;
        }
        MenuDefinition definition = registry.get(menuId);
        if (definition == null) {
            return OpenResult.UNKNOWN_MENU;
        }
        IMenuPlatform platform = menuPlatform();
        if (platform == null) {
            return OpenResult.UNSUPPORTED_PLATFORM;
        }

        UUID viewer = uuidOf(player);
        if (viewer == null) {
            return OpenResult.NO_PLAYER;
        }

        MenuSession existing = sessions.get(viewer);
        Map<String, String> values = new LinkedHashMap<>();
        if (existing != null) {
            values.putAll(existing.contextValues());
        }
        if (contextValues != null) {
            values.putAll(contextValues);
        }

        if (enforceAccess) {
            OpenResult access = accessResult(player, definition, values);
            if (access != OpenResult.OPENED) {
                return access;
            }
        }

        try {
            openResolved(player, viewer, definition, values, platform);
            return OpenResult.OPENED;
        } catch (RuntimeException failure) {
            services.getLogger().warn("Paradigm menus: failed to open '{}' for {}: {}",
                    definition.id, player.getName(), failure.toString());
            return OpenResult.FAILED;
        }
    }

    private OpenResult accessResult(IPlayer player, MenuDefinition definition, Map<String, String> values) {
        if (player == null) {
            return OpenResult.NO_PLAYER;
        }
        if (definition == null) {
            return OpenResult.UNKNOWN_MENU;
        }
        if (!definition.permission.isBlank()
                && !services.getPermissionsHandler().hasPermission(player, definition.permission)) {
            return OpenResult.NO_PERMISSION;
        }
        ActionContext probe = buildContext(player, definition, values);
        if (!dispatcher.testAll(definition.openConditions, probe)) {
            return OpenResult.CONDITIONS_FAILED;
        }
        return OpenResult.OPENED;
    }

    private void openResolved(IPlayer player, UUID viewer, MenuDefinition definition,
            Map<String, String> values, IMenuPlatform platform) {
        if (platform == null) {
            throw new IllegalStateException("Menu platform is unavailable.");
        }

        MenuSession previous = sessions.get(viewer);
        String previousMenuId = previous != null && !previous.isClosed() ? previous.menuId() : null;

        MenuSession session = new MenuSession(viewer, player.getName(), definition.id);
        if (previous != null) {
            List<String> previousHistory = previous.historySnapshot();
            for (int index = previousHistory.size() - 1; index >= 0; index--) {
                session.pushHistory(previousHistory.get(index));
            }
        }
        if (previousMenuId != null && !previousMenuId.equals(definition.id)) {
            session.pushHistory(previousMenuId);
        }
        session.putContext(values);
        session.putContext("menu", definition.id);
        session.putContext("viewer", player.getName());

        // Rendering and title resolution may invoke placeholders/conditions and can fail.
        // Do all of that before mutating the currently open session.
        ActionContext context = buildContext(player, definition, session.contextValues());
        MenuRenderer.Rendered rendered = renderer.render(definition, player, context);
        IComponent title = renderer.title(definition, player, context);

        if (previous != null) {
            previous.setNavigating(true);
        }
        sessions.put(viewer, session);

        IMenuPlatform.Handle handle;
        try {
            handle = platform.open(player, title, definition.size(),
                    rendered.items(), new SessionListener(session));
            if (handle == null) {
                throw new IllegalStateException("The platform did not provide a menu handle.");
            }
        } catch (RuntimeException failure) {
            sessions.remove(viewer, session);
            if (previous != null && !previous.isClosed()) {
                previous.setNavigating(false);
                sessions.put(viewer, previous);
            }
            throw failure;
        }

        if (previous != null) {
            previous.cancelRefreshTask();
            previous.markClosed();
        }
        session.setHandle(handle);
        session.applyRender(rendered);
        scheduleRefresh(session, definition);
    }

    public OpenResult navigate(IPlayer player, String menuId) {
        return open(player, menuId, null);
    }

    public OpenResult back(IPlayer player) {
        UUID viewer = uuidOf(player);
        MenuSession session = viewer != null ? sessions.get(viewer) : null;
        if (session == null) {
            return OpenResult.FAILED;
        }
        String target = session.popHistory();
        if (target == null) {
            close(player);
            return OpenResult.OPENED;
        }
        List<String> retained = session.historySnapshot();
        OpenResult result = open(player, target, null);
        if (result == OpenResult.OPENED) {
            MenuSession fresh = sessions.get(viewer);
            if (fresh != null) {
                fresh.clearHistory();
                for (int index = retained.size() - 1; index >= 0; index--) {
                    fresh.pushHistory(retained.get(index));
                }
            }
        }
        return result;
    }

    public void close(IPlayer player) {
        UUID viewer = uuidOf(player);
        if (viewer == null) {
            return;
        }
        MenuSession session = sessions.get(viewer);
        if (session == null) {
            return;
        }
        terminateSession(session, player, true);
    }

    public void closeAll() {
        for (MenuSession session : List.copyOf(sessions.values())) {
            session.cancelRefreshTask();
            session.markClosed();
            IMenuPlatform.Handle handle = session.handle();
            sessions.remove(session.viewer(), session);
            if (handle != null) {
                handle.close();
            }
        }
        IMenuPlatform platform = menuPlatform();
        if (platform != null) {
            platform.closeAll();
        }
    }

    public void handleDisconnect(UUID viewer) {
        if (viewer == null) {
            return;
        }
        MenuSession session = sessions.remove(viewer);
        if (session != null) {
            session.cancelRefreshTask();
            session.markClosed();
            session.setHandle(null);
        }
    }

    public void onReload() {
        IMenuPlatform platform = menuPlatform();
        for (MenuSession session : List.copyOf(sessions.values())) {
            MenuDefinition definition = registry.get(session.menuId());
            IPlayer player = playerOf(session.viewer());
            if (definition == null || player == null || platform == null) {
                closeSession(session);
                continue;
            }

            Map<String, String> values = session.contextValues();
            OpenResult access = accessResult(player, definition, values);
            if (access != OpenResult.OPENED) {
                closeSession(session);
                continue;
            }

            try {
                openResolved(player, session.viewer(), definition, values, platform);
            } catch (RuntimeException failure) {
                closeSession(session);
            }
        }
        renderer.resetInvalidItemReports();
    }

    private void closeSession(MenuSession session) {
        terminateSession(session, null, true);
    }

    private void terminateSession(MenuSession session, @Nullable IPlayer player, boolean closeHandle) {
        session.cancelRefreshTask();
        session.markClosed();
        IMenuPlatform.Handle handle = session.handle();
        sessions.remove(session.viewer(), session);
        if (closeHandle && handle != null) {
            handle.close();
        }
        MenuDefinition definition = registry.get(session.menuId());
        IPlayer resolvedPlayer = player != null ? player : playerOf(session.viewer());
        if (definition != null && resolvedPlayer != null && !definition.onClose.isEmpty()) {
            dispatcher.execute(definition.onClose, buildContext(resolvedPlayer, definition, session.contextValues()));
        }
    }

    public void refresh(MenuSession session) {
        if (session == null || session.isClosed() || !active) {
            return;
        }
        MenuDefinition definition = registry.get(session.menuId());
        IPlayer player = playerOf(session.viewer());
        IMenuPlatform.Handle handle = session.handle();
        if (definition == null || player == null || handle == null || !handle.isOpen()) {
            closeSession(session);
            return;
        }
        ActionContext context = buildContext(player, definition, session.contextValues());
        MenuRenderer.Rendered rendered = renderer.render(definition, player, context);
        Map<Integer, IMenuPlatform.ItemSpec> changed = session.diff(rendered);
        Set<Integer> refreshable = refreshableSlots(definition);
        if (!changed.isEmpty()) {
            for (Map.Entry<Integer, IMenuPlatform.ItemSpec> entry : changed.entrySet()) {
                if (refreshable.contains(entry.getKey())) {
                    handle.setItem(entry.getKey(), entry.getValue());
                }
            }
        }
        session.applyPartialRender(rendered, refreshable);
    }

    private static Set<Integer> refreshableSlots(MenuDefinition definition) {
        Set<Integer> slots = new HashSet<>();
        boolean anyDeclared = false;
        for (MenuSlot candidate : definition.slots) {
            if (candidate != null && candidate.refresh) {
                anyDeclared = true;
                slots.add(candidate.slot);
            }
        }
        if (!anyDeclared) {
            for (int slot = 0; slot < definition.size(); slot++) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private void scheduleRefresh(MenuSession session, MenuDefinition definition) {
        if (!definition.isDynamic()) {
            return;
        }
        int period = definition.refreshSeconds > 0 ? definition.refreshSeconds : 5;
        ScheduledFuture<?> task = services.getTaskScheduler().scheduleAtFixedRate(
                () -> refresh(session), period, period, TimeUnit.SECONDS);
        session.setRefreshTask(task);
    }

    private void handleSlotActivated(MenuSession session, IPlayer player, int slot, MenuClickKind kind) {
        if (!active || session == null || session.isClosed()) {
            return;
        }
        if (!kind.activatesSlot()) {
            return;
        }
        if (!session.isInteractable(slot)) {
            return;
        }
        MenuDefinition definition = registry.get(session.menuId());
        if (definition == null) {
            close(player);
            return;
        }
        MenuSlot menuSlot = definition.slotAt(slot);
        if (menuSlot == null || !menuSlot.hasAnyActions()) {
            return;
        }
        ActionContext context = buildContext(player, definition, session.contextValues())
                .toBuilder()
                .value("click", kind.token())
                .value("slot", String.valueOf(slot))
                .build();
        if (!dispatcher.testAll(menuSlot.visibleIf, context)) {
            return;
        }
        List<CustomCommand.Action> actions = menuSlot.actionsFor(kind);
        if (actions.isEmpty()) {
            return;
        }
        if (!session.beginAction()) {
            return;
        }
        try {
            dispatcher.execute(actions, context);
        } finally {
            session.endAction();
        }
    }

    private void handleClosed(MenuSession session, IPlayer player) {
        if (session == null || session.isNavigating() || session.isClosed()) {
            return;
        }
        terminateSession(session, player, false);
    }

    public ActionContext buildContext(IPlayer player, MenuDefinition definition, Map<String, String> values) {
        ActionContext.Builder builder = ActionContext.builder(services)
                .player(player)
                .origin("menu")
                .value("menu", definition != null ? definition.id : "")
                .value("viewer", player != null ? player.getName() : "");
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                builder.value(entry.getKey(), entry.getValue());
            }
        }
        return builder.build();
    }

    public List<String> validateReferences() {
        List<String> problems = new ArrayList<>();
        for (MenuDefinition definition : registry.all()) {
            collectMenuTargets(definition.slots, definition.id, problems);
        }
        return problems;
    }

    private void collectMenuTargets(List<MenuSlot> slots, String owner, List<String> problems) {
        if (slots == null) {
            return;
        }
        for (MenuSlot slot : slots) {
            if (slot == null) {
                continue;
            }
            checkActions(slot.actions, owner, slot.slot, problems);
            checkActions(slot.leftActions, owner, slot.slot, problems);
            checkActions(slot.rightActions, owner, slot.slot, problems);
        }
    }

    private void checkActions(List<CustomCommand.Action> actions, String owner, int slot, List<String> problems) {
        if (actions == null) {
            return;
        }
        for (CustomCommand.Action action : actions) {
            if (action == null) {
                continue;
            }
            if (MenuActions.OPEN_MENU.equals(action.getType())) {
                String target = MenuActions.targetOf(action);
                if (target == null || target.isBlank()) {
                    problems.add("Menu '" + owner + "' slot " + slot + " has an open_menu action without a target.");
                } else if (!registry.contains(target)) {
                    problems.add("Menu '" + owner + "' slot " + slot + " opens unknown menu '" + target + "'.");
                }
            }
            checkActions(action.getOnSuccess(), owner, slot, problems);
            checkActions(action.getOnFailure(), owner, slot, problems);
        }
    }

    @Nullable
    private IMenuPlatform menuPlatform() {
        return services.getPlatformAdapter() != null ? services.getPlatformAdapter().getMenuPlatform() : null;
    }

    @Nullable
    private IPlayer playerOf(UUID viewer) {
        return viewer != null && services.getPlatformAdapter() != null
                ? services.getPlatformAdapter().getPlayerByUuid(viewer.toString())
                : null;
    }

    @Nullable
    private static UUID uuidOf(IPlayer player) {
        if (player == null || player.getUUID() == null) {
            return null;
        }
        try {
            return UUID.fromString(player.getUUID());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private final class SessionListener implements IMenuPlatform.ClickListener {
        private final MenuSession session;

        private SessionListener(MenuSession session) {
            this.session = session;
        }

        @Override
        public void onSlotActivated(IPlayer player, int slot, IMenuPlatform.ClickKind kind) {
            if (sessions.get(session.viewer()) != session) {
                return;
            }
            handleSlotActivated(session, player, slot, MenuClickKind.from(kind));
        }

        @Override
        public void onClosed(IPlayer player) {
            if (sessions.get(session.viewer()) != session) {
                return;
            }
            handleClosed(session, player);
        }
    }
}
