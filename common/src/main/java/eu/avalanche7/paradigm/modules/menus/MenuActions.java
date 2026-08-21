package eu.avalanche7.paradigm.modules.menus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.modules.actions.ActionContext;
import eu.avalanche7.paradigm.modules.actions.ActionRegistry;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class MenuActions {

    public static final String OPEN_MENU = "open_menu";
    public static final String CLOSE_MENU = "close_menu";
    public static final String MENU_BACK = "menu_back";

    private MenuActions() {
    }

    public static void register(ActionRegistry registry, Services services, Supplier<MenuService> serviceSupplier) {
        registry.register(OPEN_MENU, (action, context) -> {
            MenuService service = serviceSupplier.get();
            IPlayer player = context.player();
            if (service == null || player == null) {
                feedback(services, context, "&cMenus are not available here.");
                return;
            }
            String target = context.expand(targetOf(action));
            if (target == null || target.isBlank()) {
                feedback(services, context, "&cThis open_menu action has no target menu.");
                return;
            }
            MenuService.OpenResult result = service.open(player, target, contextValues(action, context));
            reportOpen(services, context, target, result);
        }, "menu");

        registry.register(CLOSE_MENU, (action, context) -> {
            MenuService service = serviceSupplier.get();
            if (service != null && context.player() != null) {
                service.close(context.player());
            }
        }, "menu_close");

        registry.register(MENU_BACK, (action, context) -> {
            MenuService service = serviceSupplier.get();
            if (service != null && context.player() != null) {
                service.back(context.player());
            }
        }, "back");
    }

    @Nullable
    public static String targetOf(CustomCommand.Action action) {
        if (action == null) {
            return null;
        }
        String menu = action.getMenu();
        if (menu != null && !menu.isBlank()) {
            return menu.trim();
        }
        List<String> text = action.getText();
        if (text != null && !text.isEmpty() && text.get(0) != null && !text.get(0).isBlank()) {
            return text.get(0).trim();
        }
        return null;
    }

    private static Map<String, String> contextValues(CustomCommand.Action action, ActionContext context) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> text = action.getText();
        if (text == null) {
            return values;
        }
        boolean skipFirst = action.getMenu() == null || action.getMenu().isBlank();
        for (int index = 0; index < text.size(); index++) {
            if (skipFirst && index == 0) {
                continue;
            }
            String line = text.get(index);
            if (line == null || !line.contains("=")) {
                continue;
            }
            int split = line.indexOf('=');
            values.put(line.substring(0, split).trim(), context.expand(line.substring(split + 1)));
        }
        return values;
    }

    private static void reportOpen(Services services, ActionContext context, String target,
            MenuService.OpenResult result) {
        switch (result) {
            case OPENED -> {
            }
            case UNKNOWN_MENU -> feedback(services, context, "&cMenu '" + target + "' does not exist.");
            case NO_PERMISSION -> feedback(services, context, "&cYou do not have permission to open that menu.");
            case CONDITIONS_FAILED -> feedback(services, context, "&cYou cannot open that menu right now.");
            case UNSUPPORTED_PLATFORM -> feedback(services, context, "&cMenus are not supported on this server build.");
            case NO_PLAYER -> feedback(services, context, "&cOnly players can open menus.");
            default -> feedback(services, context, "&cFailed to open menu '" + target + "'.");
        }
    }

    private static void feedback(Services services, ActionContext context, String rawMessage) {
        IPlayer player = context.player();
        if (services.getMessageParser() == null || services.getPlatformAdapter() == null) {
            return;
        }
        IComponent message = services.getMessageParser().parseMessage(rawMessage, player);
        if (context.source() != null) {
            services.getPlatformAdapter().sendFailure(context.source(), message);
        } else if (player != null) {
            services.getPlatformAdapter().sendSystemMessage(player, message);
        }
    }
}
