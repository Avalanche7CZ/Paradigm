package eu.avalanche7.paradigm.core;

import java.util.ArrayList;
import java.util.List;

import eu.avalanche7.paradigm.modules.Announcements;
import eu.avalanche7.paradigm.modules.CommandManager;
import eu.avalanche7.paradigm.modules.Mentions;
import eu.avalanche7.paradigm.modules.Restart;
import eu.avalanche7.paradigm.modules.StorageLifecycle;
import eu.avalanche7.paradigm.modules.chat.GroupChat;
import eu.avalanche7.paradigm.modules.chat.JoinLeaveMessages;
import eu.avalanche7.paradigm.modules.chat.MOTD;
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
import eu.avalanche7.paradigm.modules.commands.Reload;
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
import eu.avalanche7.paradigm.modules.commands.admin.VanishCommand;
import eu.avalanche7.paradigm.modules.commands.admin.WhoisCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.BanCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.IpBanCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.JailCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.KickCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.MuteCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.TempBanCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.TempMuteCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.WarnCommand;
import eu.avalanche7.paradigm.modules.dashboard.LocalDashboardModule;
import eu.avalanche7.paradigm.modules.discord.DiscordModule;
import eu.avalanche7.paradigm.modules.holograms.Holograms;
import eu.avalanche7.paradigm.modules.tab.Tablist;
import eu.avalanche7.paradigm.utils.GroupChatManager;

public final class ParadigmModules {

    private ParadigmModules() {
    }

    public static List<ParadigmModule> compose(GroupChatManager groupChatManager) {
        List<ParadigmModule> modules = new ArrayList<>();
        core(modules);
        chat(modules, groupChatManager);
        playerCommands(modules);
        moderationCommands(modules);
        administration(modules);
        return List.copyOf(modules);
    }

    private static void core(List<ParadigmModule> modules) {
        modules.add(new StorageLifecycle());
        modules.add(new Help());
        modules.add(new Announcements());
        modules.add(new MOTD());
        modules.add(new Tablist());
        modules.add(new Mentions());
        modules.add(new Restart());
    }

    private static void chat(List<ParadigmModule> modules, GroupChatManager groupChatManager) {
        modules.add(new StaffChat());
        modules.add(new PrivateMessages());
        modules.add(new GroupChat(groupChatManager));
        modules.add(new JoinLeaveMessages());
        modules.add(new DiscordModule());
    }

    private static void playerCommands(List<ParadigmModule> modules) {
        modules.add(new CommandManager());
        modules.add(new Holograms());
        modules.add(new HomeCommand());
        modules.add(new TpaCommand());
        modules.add(new WarpCommand());
        modules.add(new SpawnCommand());
        modules.add(new RtpCommand());
        modules.add(new SeenCommand());
        modules.add(new IgnoreCommand());
        modules.add(new GamemodeCommand());
        modules.add(new FlyCommand());
        modules.add(new ClearInventoryCommand());
        modules.add(new TimeWeatherCommand());
        modules.add(new SpeedCommand());
        modules.add(new FeedCommand());
        modules.add(new HealCommand());
    }

    private static void moderationCommands(List<ParadigmModule> modules) {
        modules.add(new KickCommand());
        modules.add(new BanCommand());
        modules.add(new TempBanCommand());
        modules.add(new IpBanCommand());
        modules.add(new MuteCommand());
        modules.add(new TempMuteCommand());
        modules.add(new WarnCommand());
        modules.add(new JailCommand());
    }

    private static void administration(List<ParadigmModule> modules) {
        modules.add(new VanishCommand());
        modules.add(new GodCommand());
        modules.add(new InventoryInspectCommand());
        modules.add(new RepairCommand());
        modules.add(new EnchantCommand());
        modules.add(new SudoCommand());
        modules.add(new NearCommand());
        modules.add(new WhoisCommand());
        modules.add(new MovementUtilityCommand());
        modules.add(new LocalDashboardModule());
        modules.add(new Reload());
    }
}
