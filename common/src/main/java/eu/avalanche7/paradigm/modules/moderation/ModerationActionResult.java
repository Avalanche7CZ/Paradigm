package eu.avalanche7.paradigm.modules.moderation;

public record ModerationActionResult(
        boolean applied,
        String code,
        String message,
        boolean confirmationRequired,
        String punishmentId
) {
}
