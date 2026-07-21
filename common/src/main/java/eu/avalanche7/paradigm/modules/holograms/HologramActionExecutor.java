package eu.avalanche7.paradigm.modules.holograms;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.util.List;

public final class HologramActionExecutor {
    private final Services services;

    public HologramActionExecutor(Services services) {
        this.services = services;
    }

    public void execute(IPlayer player, List<HologramAction> actions) {
        execute(new Context() {
            @Override public IComponent message(String text, IPlayer viewer) { return HologramActionExecutor.this.message(text, viewer); }
            @Override public String resolve(String text, IPlayer viewer) { return HologramActionExecutor.this.resolve(text, viewer); }
            @Override public void sendMessage(IPlayer viewer, IComponent message) { services.getPlatformAdapter().sendSystemMessage(viewer, message); }
            @Override public void sendActionBar(IPlayer viewer, IComponent message) { services.getPlatformAdapter().sendActionBar(viewer, message); }
            @Override public void sendTitle(IPlayer viewer, IComponent title, IComponent subtitle) { services.getPlatformAdapter().sendTitle(viewer, title, subtitle); }
            @Override public void playSound(IPlayer viewer, HologramAction action) { services.getPlatformAdapter().playSound(viewer, action.sound, action.soundCategory, action.volume, action.pitch); }
            @Override public void runPlayerCommand(IPlayer viewer, String command) { HologramActionExecutor.this.runPlayerCommand(viewer, command); }
            @Override public void runConsoleCommand(String command) { services.getPlatformAdapter().executeCommandAsConsole(command); }
        }, player, actions);
    }

    public static void execute(Context context, IPlayer player, List<HologramAction> actions) {
        if (player == null || actions == null) return;
        for (HologramAction action : actions) {
            if (action == null) continue;
            switch (action.type) {
                case "message" -> context.sendMessage(player, context.message(action.text, player));
                case "actionbar" -> context.sendActionBar(player, context.message(action.text, player));
                case "title" -> context.sendTitle(player, context.message(action.text, player), context.message(action.subtitle, player));
                case "sound" -> context.playSound(player, action);
                case "player_command" -> context.runPlayerCommand(player, context.resolve(action.command, player));
                case "console_command" -> context.runConsoleCommand(context.resolve(action.command, player));
                default -> throw new IllegalArgumentException("Unknown hologram action type: " + action.type);
            }
        }
    }

    private void runPlayerCommand(IPlayer player, String command) {
        ICommandSource source = services.getPlatformAdapter().createCommandSourceForPlayer(player);
        if (source == null) throw new IllegalStateException("This platform cannot create a player command source.");
        services.getPlatformAdapter().executeCommandAs(source, resolve(command, player));
    }

    private eu.avalanche7.paradigm.platform.Interfaces.IComponent message(String text, IPlayer player) {
        return services.getMessageParser().parseMessage(text != null ? text : "", player);
    }

    private String resolve(String text, IPlayer player) {
        return services.getPlatformAdapter().replacePlaceholders(text != null ? text : "", player);
    }

    public interface Context {
        IComponent message(String text, IPlayer viewer);
        String resolve(String text, IPlayer viewer);
        void sendMessage(IPlayer viewer, IComponent message);
        void sendActionBar(IPlayer viewer, IComponent message);
        void sendTitle(IPlayer viewer, IComponent title, IComponent subtitle);
        void playSound(IPlayer viewer, HologramAction action);
        void runPlayerCommand(IPlayer viewer, String command);
        void runConsoleCommand(String command);
    }
}
