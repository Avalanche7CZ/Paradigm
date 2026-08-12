package eu.avalanche7.paradigm.modules.commands.admin;

import java.util.List;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public class EnchantCommand extends AbstractAdminCommand {
    @Override
    public String getName() {
        return "Enchant";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = builder()
                .literal("enchant")
                .requires(src -> allowed(src, "enchant", ParadigmPermissions.ENCHANT))
                .then(builder()
                        .argument("enchantment", ICommandBuilder.ArgumentType.WORD)
                        .suggests(List.of("minecraft:sharpness", "minecraft:efficiency", "minecraft:unbreaking", "minecraft:mending"))
                        .executes(ctx -> enchant(ctx.getSource(), ctx.getSource().getPlayer(), ctx.getStringArgument("enchantment"), 1))
                        .then(builder()
                                .argument("level", ICommandBuilder.ArgumentType.INTEGER)
                                .executes(ctx -> enchant(ctx.getSource(), ctx.getSource().getPlayer(), ctx.getStringArgument("enchantment"), ctx.getIntArgument("level")))
                                .then(builder()
                                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                                        .executes(ctx -> enchant(ctx.getSource(), ctx.getPlayerArgument("player"), ctx.getStringArgument("enchantment"), ctx.getIntArgument("level"))))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int enchant(ICommandSource source, IPlayer target, String enchantment, int level) {
        if (target == null) {
            send(source, "admin.player_not_found", "Player not found.");
            return 0;
        }
        if (!canTargetOther(source.getPlayer(), target, ParadigmPermissions.ENCHANT_OTHERS)) {
            send(source, "admin.no_permission_others", "You do not have permission to affect other players.");
            return 0;
        }
        int clamped = Math.max(1, Math.min(level, 255));
        String normalizedEnchantment = EnchantmentIds.normalize(enchantment);
        if (!services.getPlatformAdapter().enchantMainHand(target, normalizedEnchantment, clamped)) {
            send(source, "admin.enchant_fail", "Could not apply {enchantment} to {player}.",
                    "{enchantment}", normalizedEnchantment, "{player}", target.getName());
            return 0;
        }
        send(source, "admin.enchant_ok", "Applied {enchantment} {level} to {player}.",
                "{enchantment}", normalizedEnchantment, "{level}", String.valueOf(clamped), "{player}", target.getName());
        return 1;
    }
}
