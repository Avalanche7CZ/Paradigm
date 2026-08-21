package eu.avalanche7.paradigm.modules.permissions;

import java.util.List;

/** Result of a track edit or rank movement. Positions are one-based. */
public record PermissionTrackResult(
        boolean applied,
        String code,
        String message,
        String track,
        Integer oldPosition,
        Integer newPosition,
        List<String> conflictingGroups
) {
    public static PermissionTrackResult of(boolean applied, String code, String message, String track, Integer oldPosition, Integer newPosition) {
        return new PermissionTrackResult(applied, code, message, track, oldPosition, newPosition, List.of());
    }
}
