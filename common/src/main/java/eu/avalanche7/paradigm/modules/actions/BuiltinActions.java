package eu.avalanche7.paradigm.modules.actions;

import java.util.List;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class BuiltinActions {

    private BuiltinActions() {
    }

    public static void register(ActionRegistry registry, Services services) {
        registry.register("message", (action, context) -> {
            List<String> text = action.getText();
            if (text == null) {
                return;
            }
            for (String line : text) {
                context.reply(context.expand(line));
            }
        });

        registry.register("actionbar", (action, context) -> {
            IPlayer player = context.player();
            if (player == null) {
                return;
            }
            List<String> text = action.getText();
            if (text == null || text.isEmpty()) {
                return;
            }
            services.getPlatformAdapter().sendActionBar(player,
                    services.getMessageParser().parseMessage(context.expand(text.get(0)), player));
        });

        registry.register("title", (action, context) -> {
            IPlayer player = context.player();
            if (player == null) {
                return;
            }
            List<String> text = action.getText();
            if (text == null || text.isEmpty()) {
                return;
            }
            IComponent title = services.getMessageParser().parseMessage(context.expand(text.get(0)), player);
            IComponent subtitle = text.size() > 1
                    ? services.getMessageParser().parseMessage(context.expand(text.get(1)), player)
                    : services.getPlatformAdapter().createEmptyComponent();
            services.getPlatformAdapter().sendTitle(player, title, subtitle);
        });

        registry.register("sound", (action, context) -> {
            IPlayer player = context.player();
            if (player == null) {
                return;
            }
            List<String> text = action.getText();
            String spec = text != null && !text.isEmpty() && text.get(0) != null ? text.get(0).trim() : "";
            if (spec.isEmpty()) {
                context.replyFailure("&cSound actions require a sound id.");
                return;
            }
            String[] parts = spec.split("\\s+");
            String soundId = parts[0];
            float volume = parts.length > 1 ? parseFloat(parts[1], 1.0F) : 1.0F;
            float pitch = parts.length > 2 ? parseFloat(parts[2], 1.0F) : 1.0F;
            String category = parts.length > 3 ? parts[3] : "master";
            services.getPlatformAdapter().playSound(player, soundId, category,
                    Math.max(0.0F, Math.min(10.0F, volume)), Math.max(0.0F, Math.min(4.0F, pitch)));
        }, "play_sound");

        registry.register("teleport", (action, context) -> {
            IPlayer player = context.player();
            IPlatformAdapter platform = services.getPlatformAdapter();
            if (player != null && action.getX() != null && action.getY() != null && action.getZ() != null) {
                platform.teleportPlayer(player, action.getX(), action.getY(), action.getZ());
            } else if (player == null) {
                context.replyFailure("&cTeleport action can only be performed by a player.");
            } else {
                context.replyFailure("&cInvalid teleport coordinates.");
            }
        });

        registry.register("run_command", (action, context) -> {
            List<String> commands = action.getCommands();
            if (commands == null) {
                return;
            }
            ICommandSource source = commandSource(services, context);
            if (source == null) {
                context.replyFailure("&cThis action requires a player command source.");
                return;
            }
            for (String command : commands) {
                services.getPlatformAdapter().executeCommandAs(source, context.expand(command));
            }
        }, "runcmd", "command", "player_command");

        registry.register("run_console", (action, context) -> {
            List<String> commands = action.getCommands();
            if (commands == null) {
                return;
            }
            for (String command : commands) {
                services.getPlatformAdapter().executeCommandAsConsole(context.expand(command));
            }
        }, "console_command");
    }

    private static float parseFloat(String value, float fallback) {
        try {
            float parsed = Float.parseFloat(value.trim());
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    static ICommandSource commandSource(Services services, ActionContext context) {
        if (context.source() != null) {
            return context.source();
        }
        IPlayer player = context.player();
        return player != null ? services.getPlatformAdapter().createCommandSourceForPlayer(player) : null;
    }

}
