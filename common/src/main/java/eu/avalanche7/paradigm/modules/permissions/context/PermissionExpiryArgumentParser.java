package eu.avalanche7.paradigm.modules.permissions.context;

import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;

/** Parses command expiry arguments into the persistence representation. */
public final class PermissionExpiryArgumentParser {
    private PermissionExpiryArgumentParser() {
    }

    public static Result parse(String duration, boolean permanent, long nowMs) {
        if (permanent) {
            return Result.ok(null);
        }
        long durationMs = DurationParser.parseToMillis(duration);
        if (durationMs <= 0L) {
            return Result.error("invalid_expiry", "Invalid expiry duration.");
        }
        try {
            return Result.ok(Math.addExact(nowMs, durationMs));
        } catch (ArithmeticException e) {
            return Result.error("invalid_expiry", "Expiry duration is too large.");
        }
    }

    public record Result(Long expiresAtMs, String code, String message) {
        public static Result ok(Long expiresAtMs) {
            return new Result(expiresAtMs, "ok", "");
        }

        public static Result error(String code, String message) {
            return new Result(null, code, message);
        }

        public boolean valid() {
            return "ok".equals(code);
        }
    }
}
