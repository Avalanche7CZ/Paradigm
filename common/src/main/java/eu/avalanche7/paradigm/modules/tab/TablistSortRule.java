package eu.avalanche7.paradigm.modules.tab;

import java.util.Locale;

public enum TablistSortRule {
    GROUP_WEIGHT_DESC,
    GROUP_WEIGHT_ASC,
    PLAYER_NAME_ASC,
    PLAYER_NAME_DESC,
    PING_ASC,
    PING_DESC;

    public static TablistSortRule parse(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
