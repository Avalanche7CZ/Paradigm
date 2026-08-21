package eu.avalanche7.paradigm.modules.menus;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.Nullable;

import eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform;

public final class MenuSession {

    public static final int MAX_HISTORY = 32;

    private final UUID viewer;
    private final String viewerName;
    private final Deque<String> history = new ArrayDeque<>();
    private final Map<String, String> contextValues = new LinkedHashMap<>();
    private final Map<Integer, String> signatures = new HashMap<>();
    private final Set<Integer> interactable = new HashSet<>();
    private final AtomicBoolean actionRunning = new AtomicBoolean();

    private volatile String menuId;
    private volatile IMenuPlatform.Handle handle;
    private volatile ScheduledFuture<?> refreshTask;
    private volatile boolean closed;
    private volatile boolean navigating;

    public MenuSession(UUID viewer, String viewerName, String menuId) {
        this.viewer = viewer;
        this.viewerName = viewerName != null ? viewerName : "";
        this.menuId = menuId;
    }

    public UUID viewer() {
        return viewer;
    }

    public String viewerName() {
        return viewerName;
    }

    public String menuId() {
        return menuId;
    }

    public boolean isClosed() {
        return closed;
    }

    public void markClosed() {
        closed = true;
    }

    public boolean isNavigating() {
        return navigating;
    }

    public void setNavigating(boolean value) {
        navigating = value;
    }

    public boolean beginAction() {
        return actionRunning.compareAndSet(false, true);
    }

    public void endAction() {
        actionRunning.set(false);
    }

    @Nullable
    public IMenuPlatform.Handle handle() {
        return handle;
    }

    public void setHandle(@Nullable IMenuPlatform.Handle handle) {
        this.handle = handle;
    }

    public void setRefreshTask(@Nullable ScheduledFuture<?> task) {
        this.refreshTask = task;
    }

    public void cancelRefreshTask() {
        ScheduledFuture<?> task = refreshTask;
        refreshTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    public Map<String, String> contextValues() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(contextValues));
    }

    public void putContext(Map<String, String> values) {
        if (values != null) {
            contextValues.putAll(values);
        }
    }

    public void putContext(String key, String value) {
        if (key != null && !key.isBlank()) {
            contextValues.put(key, value != null ? value : "");
        }
    }

    public void pushHistory(String previousMenuId) {
        if (previousMenuId == null || previousMenuId.isBlank()) {
            return;
        }
        history.push(previousMenuId);
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
    }

    @Nullable
    public String popHistory() {
        return history.poll();
    }

    public boolean hasHistory() {
        return !history.isEmpty();
    }

    public List<String> historySnapshot() {
        return List.copyOf(history);
    }

    public void clearHistory() {
        history.clear();
    }

    public void adoptMenu(String newMenuId) {
        this.menuId = newMenuId;
        signatures.clear();
        interactable.clear();
    }

    public boolean isInteractable(int slot) {
        return interactable.contains(slot);
    }

    public void applyRender(MenuRenderer.Rendered rendered) {
        if (rendered == null) {
            return;
        }
        signatures.clear();
        signatures.putAll(rendered.signatures());
        interactable.clear();
        interactable.addAll(rendered.interactable());
    }

    /**
     * Applies only slots that were actually eligible for a physical refresh.
     * Keeping the logical session state scoped to the same slots prevents a
     * stale client item from becoming logically clickable after a selective
     * refresh.
     */
    public void applyPartialRender(MenuRenderer.Rendered rendered, Set<Integer> refreshedSlots) {
        if (rendered == null || refreshedSlots == null || refreshedSlots.isEmpty()) {
            return;
        }
        for (Integer slot : refreshedSlots) {
            if (slot == null) {
                continue;
            }
            String signature = rendered.signatures().get(slot);
            if (signature == null) {
                signatures.remove(slot);
            } else {
                signatures.put(slot, signature);
            }
            if (rendered.interactable().contains(slot)) {
                interactable.add(slot);
            } else {
                interactable.remove(slot);
            }
        }
    }

    public Map<Integer, IMenuPlatform.ItemSpec> diff(MenuRenderer.Rendered rendered) {
        Map<Integer, IMenuPlatform.ItemSpec> changed = new LinkedHashMap<>();
        if (rendered == null) {
            return changed;
        }
        for (Map.Entry<Integer, String> entry : rendered.signatures().entrySet()) {
            String previous = signatures.get(entry.getKey());
            if (previous == null || !previous.equals(entry.getValue())) {
                changed.put(entry.getKey(), rendered.items().get(entry.getKey()));
            }
        }
        for (Integer slot : signatures.keySet()) {
            if (!rendered.signatures().containsKey(slot)) {
                changed.put(slot, null);
            }
        }
        return changed;
    }
}
