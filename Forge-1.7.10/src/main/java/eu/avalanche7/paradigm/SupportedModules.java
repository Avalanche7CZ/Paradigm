package eu.avalanche7.paradigm;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.Mentions;
import eu.avalanche7.paradigm.modules.StorageLifecycle;
import eu.avalanche7.paradigm.modules.afk.AfkModule;
import eu.avalanche7.paradigm.modules.chat.GroupChat;
import eu.avalanche7.paradigm.modules.chat.JoinLeaveMessages;
import eu.avalanche7.paradigm.modules.chat.PrivateMessages;
import eu.avalanche7.paradigm.modules.chat.StaffChat;
import eu.avalanche7.paradigm.modules.commands.ClearInventoryCommand;
import eu.avalanche7.paradigm.modules.commands.FeedCommand;
import eu.avalanche7.paradigm.modules.commands.FlyCommand;
import eu.avalanche7.paradigm.modules.commands.GamemodeCommand;
import eu.avalanche7.paradigm.modules.commands.HealCommand;
import eu.avalanche7.paradigm.modules.commands.Help;
import eu.avalanche7.paradigm.modules.commands.HomeCommand;
import eu.avalanche7.paradigm.modules.commands.IgnoreCommand;
import eu.avalanche7.paradigm.modules.commands.RtpCommand;
import eu.avalanche7.paradigm.modules.commands.SeenCommand;
import eu.avalanche7.paradigm.modules.commands.SpawnCommand;
import eu.avalanche7.paradigm.modules.commands.SpeedCommand;
import eu.avalanche7.paradigm.modules.commands.TimeWeatherCommand;
import eu.avalanche7.paradigm.modules.commands.TpaCommand;
import eu.avalanche7.paradigm.modules.commands.WarpCommand;
import eu.avalanche7.paradigm.modules.commands.admin.EnchantCommand;
import eu.avalanche7.paradigm.modules.commands.admin.GodCommand;
import eu.avalanche7.paradigm.modules.commands.admin.InventoryInspectCommand;
import eu.avalanche7.paradigm.modules.commands.admin.MovementUtilityCommand;
import eu.avalanche7.paradigm.modules.commands.admin.NearCommand;
import eu.avalanche7.paradigm.modules.commands.admin.RepairCommand;
import eu.avalanche7.paradigm.modules.commands.admin.SudoCommand;
import eu.avalanche7.paradigm.modules.commands.admin.WhoisCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.BanCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.IpBanCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.JailCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.KickCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.MuteCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.TempBanCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.TempMuteCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.WarnCommand;
import eu.avalanche7.paradigm.modules.commands.shared.CommandCatalog;
import eu.avalanche7.paradigm.modules.playtime.PlaytimeModule;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;

final class SupportedModules {
    private static final Set<Class<? extends ParadigmModule>> TYPES = Set.of(
            eu.avalanche7.paradigm.modules.actions.PlayerInputModule.class,
            eu.avalanche7.paradigm.modules.CommandManager.class,
            eu.avalanche7.paradigm.modules.commands.Reload.class,
            eu.avalanche7.paradigm.modules.tickets.TicketsModule.class,
            eu.avalanche7.paradigm.modules.dashboard.LocalDashboardModule.class,
            eu.avalanche7.paradigm.modules.discord.DiscordModule.class,
            eu.avalanche7.paradigm.modules.menus.Menus.class, eu.avalanche7.paradigm.modules.holograms.Holograms.class, eu.avalanche7.paradigm.modules.commands.admin.VanishCommand.class, eu.avalanche7.paradigm.modules.tab.Tablist.class, eu.avalanche7.paradigm.modules.chat.MOTD.class, eu.avalanche7.paradigm.modules.Announcements.class, eu.avalanche7.paradigm.modules.Restart.class, StorageLifecycle.class, Help.class, HealCommand.class, FeedCommand.class,
            FlyCommand.class, SpeedCommand.class, TimeWeatherCommand.class,
            ClearInventoryCommand.class, GamemodeCommand.class, GodCommand.class, HomeCommand.class, TpaCommand.class,
            WarpCommand.class, SpawnCommand.class, RtpCommand.class,
            InventoryInspectCommand.class, RepairCommand.class, EnchantCommand.class, SudoCommand.class, NearCommand.class, WhoisCommand.class, MovementUtilityCommand.class, AfkModule.class, PlaytimeModule.class, KickCommand.class,
            BanCommand.class, TempBanCommand.class, IpBanCommand.class, MuteCommand.class, TempMuteCommand.class, WarnCommand.class, JailCommand.class, SeenCommand.class, IgnoreCommand.class, PrivateMessages.class, StaffChat.class, GroupChat.class, Mentions.class, JoinLeaveMessages.class);

    private SupportedModules() {
    }

    static boolean supports(ParadigmModule module) {
        return TYPES.contains(module.getClass());
    }

    static Set<String> commandIds(Collection<ParadigmModule> modules, IPlatformAdapter platform) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add("paradigm.command");
        for (ParadigmModule module : modules) {
            if (!supports(module)) {
                continue;
            }
            if (module instanceof Help) {
                ids.add("paradigm.help");
            }
            for (CommandCatalog.Entry entry : CommandCatalog.entriesForModule(module)) {
                if ((entry.id().equals("gmsp") || entry.id().equals("spectator"))
                        && !platform.supportsGameMode("spectator")) {
                    continue;
                }
                ids.add(entry.id());
            }
        }
        return Set.copyOf(ids);
    }

    static void registerCommands(ParadigmModule module, Object dispatcher, Services services) {
        if (!module.isEnabled(services)) {
            return;
        }
        if (module instanceof Help && !services.getCommandToggleStore().isEnabled("paradigm.help")) {
            return;
        }
        module.registerCommands(dispatcher, null, services);
    }
}
