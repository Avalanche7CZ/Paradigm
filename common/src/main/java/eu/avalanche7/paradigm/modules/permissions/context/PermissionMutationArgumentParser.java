package eu.avalanche7.paradigm.modules.permissions.context;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;

public final class PermissionMutationArgumentParser {
    private final PermissionContextArgumentParser contextParser;

    public PermissionMutationArgumentParser(Supplier<ServerIdentity> identitySupplier) {
        this.contextParser = new PermissionContextArgumentParser(identitySupplier);
    }

    public Result parse(String tail, ICommandSource source, boolean allowExpiry) {
        return parse(tail, source, allowExpiry, false);
    }

    public Result parse(String tail, ICommandSource source, boolean allowExpiry, boolean allowTrackFlags) {
        List<String> tokens = tokenize(tail);
        List<String> rawContexts = new ArrayList<>();
        String expiry = null;
        boolean permanent = false;
        boolean dontAddToFirst = false;
        boolean dontRemoveFromFirst = false;

        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if ("--context".equals(token)) {
                if (++index >= tokens.size()) {
                    return Result.error("invalid_context", "--context requires key=value.");
                }
                rawContexts.add(tokens.get(index));
            } else if ("--expires".equals(token)) {
                if (!allowExpiry) {
                    return Result.error("invalid_expiry", "Expiry is not valid for this remove operation.");
                }
                if (expiry != null || ++index >= tokens.size()) {
                    return Result.error("invalid_expiry", "--expires requires one duration.");
                }
                expiry = tokens.get(index);
            } else if ("--permanent".equals(token)) {
                if (!allowExpiry || permanent || expiry != null) {
                    return Result.error("invalid_expiry", "--permanent cannot be combined with --expires.");
                }
                permanent = true;
            } else if ("--dont-add-to-first".equals(token)) {
                if (!allowTrackFlags) return Result.error("invalid_context", "Unknown permission flag: " + token);
                if (dontAddToFirst) return Result.error("invalid_context", "--dont-add-to-first may only be used once.");
                dontAddToFirst = true;
            } else if ("--dont-remove-from-first".equals(token)) {
                if (!allowTrackFlags) return Result.error("invalid_context", "Unknown permission flag: " + token);
                if (dontRemoveFromFirst) return Result.error("invalid_context", "--dont-remove-from-first may only be used once.");
                dontRemoveFromFirst = true;
            } else {
                return Result.error("invalid_context", "Unknown permission flag: " + token);
            }
        }

        PermissionContextArgumentParser.Result contexts = contextParser.parse(rawContexts, source);
        if (!contexts.valid()) {
            return Result.error(contexts.code(), contexts.message());
        }
        PermissionExpiryArgumentParser.Result expires = PermissionExpiryArgumentParser.parse(expiry, permanent || expiry == null, System.currentTimeMillis());
        if (!expires.valid()) {
            return Result.error(expires.code(), expires.message());
        }
        return Result.ok(contexts.contexts(), expires.expiresAtMs(), expiry != null || permanent, dontAddToFirst, dontRemoveFromFirst);
    }

    private static List<String> tokenize(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.trim().split("\\s+"));
    }

    public record Result(PermissionContextSet contexts, Long expiresAtMs, boolean expirySpecified, boolean dontAddToFirst,
                         boolean dontRemoveFromFirst, String code, String message) {
        public static Result ok(PermissionContextSet contexts, Long expiresAtMs, boolean expirySpecified, boolean dontAddToFirst, boolean dontRemoveFromFirst) {
            return new Result(contexts, expiresAtMs, expirySpecified, dontAddToFirst, dontRemoveFromFirst, "ok", "");
        }

        public static Result error(String code, String message) {
            return new Result(null, null, false, false, false, code, message);
        }

        public boolean valid() {
            return contexts != null;
        }
    }
}
