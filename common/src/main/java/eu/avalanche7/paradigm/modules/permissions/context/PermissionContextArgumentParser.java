package eu.avalanche7.paradigm.modules.permissions.context;

import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Parses repeatable key=value permission-context arguments for command mutations. */
public final class PermissionContextArgumentParser {
    private final Supplier<ServerIdentity> identitySupplier;

    public PermissionContextArgumentParser(Supplier<ServerIdentity> identitySupplier) {
        this.identitySupplier = identitySupplier;
    }

    public Result parse(List<String> rawContexts, ICommandSource source) {
        Map<String, String> contexts = new LinkedHashMap<>();
        if (rawContexts == null || rawContexts.isEmpty()) {
            return Result.ok(PermissionContextSet.empty());
        }

        for (String raw : rawContexts) {
            int separator = raw != null ? raw.indexOf('=') : -1;
            if (separator <= 0 || separator == raw.length() - 1) {
                return Result.error("invalid_context", "Invalid context. Use key=value.");
            }
            String key = raw.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = raw.substring(separator + 1).trim();
            PermissionContextType type = PermissionContextType.fromKey(key);
            if (type == null) {
                return Result.error("unsupported_context", "Unsupported permission context: " + key);
            }
            if (value.isEmpty()) {
                return Result.error("invalid_context", "Permission context values cannot be empty.");
            }
            if ("current".equalsIgnoreCase(value)) {
                value = currentValue(type, source);
                if (value == null) {
                    return Result.error("context_current_unavailable", "Current " + key + " context is unavailable.");
                }
            }
            contexts.put(key, value);
        }

        try {
            return Result.ok(PermissionContextSet.of(contexts));
        } catch (IllegalArgumentException e) {
            return Result.error("invalid_context", e.getMessage());
        }
    }

    private String currentValue(PermissionContextType type, ICommandSource source) {
        ServerIdentity identity = identitySupplier != null ? identitySupplier.get() : null;
        return switch (type) {
            case SERVER -> nonBlank(identity != null ? identity.serverId() : null);
            case NETWORK -> nonBlank(identity != null ? identity.networkId() : null);
            case WORLD, DIMENSION -> {
                IPlayer player = source != null ? source.getPlayer() : null;
                yield nonBlank(player != null ? player.getWorldId() : null);
            }
        };
    }

    private static String nonBlank(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    public record Result(PermissionContextSet contexts, String code, String message) {
        public static Result ok(PermissionContextSet contexts) {
            return new Result(contexts != null ? contexts : PermissionContextSet.empty(), "ok", "");
        }

        public static Result error(String code, String message) {
            return new Result(null, code, message);
        }

        public boolean valid() {
            return contexts != null;
        }
    }
}
