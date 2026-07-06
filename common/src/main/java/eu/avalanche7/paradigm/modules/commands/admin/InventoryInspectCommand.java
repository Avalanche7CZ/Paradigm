package eu.avalanche7.paradigm.modules.commands.admin;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.PlayerReflection;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.util.List;

public class InventoryInspectCommand extends AbstractAdminCommand {
    private static final int MAX_LINES = 20;

    @Override
    public String getName() {
        return "InventoryInspect";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        registerInventory();
        registerEnder();
    }

    private void registerInventory() {
        ICommandBuilder cmd = builder()
                .literal("invsee")
                .requires(src -> allowed(src, "invsee", PermissionsHandler.INVSEE_PERMISSION, PermissionsHandler.INVSEE_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> inspect(ctx.getSource(), ctx.getPlayerArgument("player"), false)));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private void registerEnder() {
        ICommandBuilder cmd = builder()
                .literal("endersee")
                .requires(src -> allowed(src, "endersee", PermissionsHandler.ENDERSEE_PERMISSION, PermissionsHandler.ENDERSEE_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> inspect(ctx.getSource(), ctx.getPlayerArgument("player"), true)));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int inspect(ICommandSource source, IPlayer target, boolean ender) {
        if (target == null) {
            send(source, "admin.player_not_found", "Player not found.");
            return 0;
        }
        List<Object> stacks = ender ? PlayerReflection.enderStacks(target) : PlayerReflection.inventoryStacks(target, true);
        send(source, "admin.inspect_header", "{type} for {player}:", "{type}", ender ? "Ender chest" : "Inventory", "{player}", target.getName());
        int shown = 0;
        int slot = 0;
        for (Object stack : stacks) {
            if (!PlayerReflection.isEmptyStack(stack)) {
                shown++;
                if (shown <= MAX_LINES) {
                    send(source, "admin.inspect_line", "#{slot}: {count}x {item}",
                            "{slot}", String.valueOf(slot),
                            "{count}", String.valueOf(PlayerReflection.stackCount(stack)),
                            "{item}", PlayerReflection.stackName(stack));
                }
            }
            slot++;
        }
        if (shown == 0) {
            send(source, "admin.inspect_empty", "No items found.");
        } else if (shown > MAX_LINES) {
            send(source, "admin.inspect_more", "...and {count} more item stacks.", "{count}", String.valueOf(shown - MAX_LINES));
        }
        return 1;
    }
}
