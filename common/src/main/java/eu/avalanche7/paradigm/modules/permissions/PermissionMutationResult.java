package eu.avalanche7.paradigm.modules.permissions;

public record PermissionMutationResult(
        boolean applied,
        String code,
        String message,
        boolean confirmationRequired
) {
}
