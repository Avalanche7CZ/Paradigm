package eu.avalanche7.paradigm.modules.menus;

import java.util.ArrayList;
import java.util.List;

import eu.avalanche7.paradigm.data.CustomCommand;

public final class MenuSlot {

    public int slot;
    public MenuItem item = new MenuItem();
    public List<CustomCommand.Condition> visibleIf = new ArrayList<>();
    public List<CustomCommand.Action> actions = new ArrayList<>();
    public List<CustomCommand.Action> leftActions = new ArrayList<>();
    public List<CustomCommand.Action> rightActions = new ArrayList<>();
    public boolean refresh;

    public MenuSlot copy() {
        MenuSlot copy = new MenuSlot();
        copy.slot = slot;
        copy.item = item != null ? item.copy() : null;
        copy.refresh = refresh;
        copy.visibleIf = new ArrayList<>(visibleIf != null ? visibleIf : List.of());
        copy.actions = new ArrayList<>(actions != null ? actions : List.of());
        copy.leftActions = new ArrayList<>(leftActions != null ? leftActions : List.of());
        copy.rightActions = new ArrayList<>(rightActions != null ? rightActions : List.of());
        return copy;
    }

    public void normalize(int size) {
        if (slot < 0 || slot >= size) {
            throw new IllegalArgumentException("Slot " + slot + " is outside the menu bounds (0-" + (size - 1) + ").");
        }
        if (item == null) {
            throw new IllegalArgumentException("Slot " + slot + " requires an item.");
        }
        item.normalize();
        visibleIf = sanitizeConditions(visibleIf);
        actions = sanitizeActions(actions);
        leftActions = sanitizeActions(leftActions);
        rightActions = sanitizeActions(rightActions);
    }

    public boolean hasAnyActions() {
        return !actions.isEmpty() || !leftActions.isEmpty() || !rightActions.isEmpty();
    }

    public List<CustomCommand.Action> actionsFor(MenuClickKind kind) {
        if (kind != null && kind.isRight() && !rightActions.isEmpty()) {
            return rightActions;
        }
        if (kind != null && kind.isLeft() && !leftActions.isEmpty()) {
            return leftActions;
        }
        return actions;
    }

    private static List<CustomCommand.Condition> sanitizeConditions(List<CustomCommand.Condition> input) {
        List<CustomCommand.Condition> out = new ArrayList<>();
        if (input != null) {
            for (CustomCommand.Condition condition : input) {
                if (condition != null) {
                    out.add(condition);
                }
            }
        }
        return out;
    }

    private static List<CustomCommand.Action> sanitizeActions(List<CustomCommand.Action> input) {
        List<CustomCommand.Action> out = new ArrayList<>();
        if (input != null) {
            for (CustomCommand.Action action : input) {
                if (action != null) {
                    out.add(action);
                }
            }
        }
        return out;
    }
}
