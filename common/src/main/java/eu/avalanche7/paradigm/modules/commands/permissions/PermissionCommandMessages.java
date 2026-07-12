package eu.avalanche7.paradigm.modules.commands.permissions;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;

final class PermissionCommandMessages {
    private PermissionCommandMessages() {
    }

    static void send(ICommandSource source, Services services, String key, String fallback, String... placeholders) {
        String raw = text(services, key, fallback, placeholders);
        IComponent message = services.getMessageParser().parseMessage(
                "<color:#A78BFA><bold>[Group]</bold></color> <color:#E5E7EB>" + raw + "</color>",
                source != null ? source.getPlayer() : null
        );
        services.getPlatformAdapter().sendSuccess(source, message, false);
    }

    static int argumentError(ICommandSource source, Services services, String code, String fallback) {
        String key = switch (code) {
            case "unsupported_context" -> "group.manage.context_unsupported";
            case "context_current_unavailable" -> "group.manage.context_current_unavailable";
            case "invalid_expiry" -> "group.manage.expiry_invalid";
            default -> "group.manage.context_invalid";
        };
        send(source, services, key, fallback);
        return 0;
    }

    static int assignmentNotFound(ICommandSource source, Services services, String assignmentId) {
        send(source, services, "group.manage.assignment_not_found", "Permission assignment {id} was not found.", "{id}", assignmentId);
        return 0;
    }

    static String text(Services services, String key, String fallback, String... placeholders) {
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        return raw;
    }
}
