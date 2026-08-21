package eu.avalanche7.paradigm.modules.menus;

import java.util.Locale;

import eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform;

public enum MenuClickKind {
    LEFT,
    RIGHT,
    MIDDLE,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    NUMBER_KEY,
    SWAP_OFFHAND,
    DOUBLE_CLICK,
    DROP,
    CONTROL_DROP,
    DRAG,
    OTHER;

    public boolean isLeft() {
        return this == LEFT || this == SHIFT_LEFT || this == DOUBLE_CLICK;
    }

    public boolean isRight() {
        return this == RIGHT || this == SHIFT_RIGHT;
    }

    public boolean isShift() {
        return this == SHIFT_LEFT || this == SHIFT_RIGHT;
    }

    public boolean activatesSlot() {
        return this != DRAG && this != OTHER;
    }

    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static MenuClickKind from(IMenuPlatform.ClickKind kind) {
        if (kind == null) {
            return OTHER;
        }
        return switch (kind) {
            case LEFT -> LEFT;
            case RIGHT -> RIGHT;
            case MIDDLE -> MIDDLE;
            case SHIFT_LEFT -> SHIFT_LEFT;
            case SHIFT_RIGHT -> SHIFT_RIGHT;
            case NUMBER_KEY -> NUMBER_KEY;
            case SWAP_OFFHAND -> SWAP_OFFHAND;
            case DOUBLE_CLICK -> DOUBLE_CLICK;
            case DROP -> DROP;
            case CONTROL_DROP -> CONTROL_DROP;
            case DRAG -> DRAG;
            case OTHER -> OTHER;
        };
    }
}
