package eu.avalanche7.paradigm;

import eu.avalanche7.paradigm.commands.AnnouncementsCommand;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.utils.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.common.config.Configuration;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;


@Mod(modid = Paradigm.MODID, name = Paradigm.NAME, version = Paradigm.VERSION, acceptableRemoteVersions = "*")
public class Paradigm {

    public static final String MODID = "paradigm";
    public static final String NAME = "Forge Announcements";
    public static final String VERSION = "12.0.4";

    private static final Logger LOGGER = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Initializing Forge Announcements mod...");
        File directory = new File(event.getSuggestedConfigurationFile().getParentFile(), "paradigm");
        if (!directory.exists()) {
            directory.mkdir();
        }
        Configuration mainConfig = new Configuration(new File(directory.getPath(), "main.cfg"));
        MainConfigHandler.init(mainConfig);
        Configuration config = new Configuration(new File(directory.getPath(), "announcements.cfg"));
        AnnouncementsConfigHandler.init(config);
        Configuration motdConfig = new Configuration(new File(directory.getPath(), "motd.cfg"));
        MOTDConfigHandler.init(motdConfig);
        Configuration mentionsConfig = new Configuration(new File(directory.getPath(), "mentions.cfg"));
        MentionConfigHandler.init(mentionsConfig);
        Configuration restartConfig = new Configuration(new File(directory.getPath(), "restart.cfg"));
        RestartConfigHandler.init(restartConfig);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new MOTD());
        MinecraftForge.EVENT_BUS.register(new Mentions());
        MinecraftForge.EVENT_BUS.register(new PermissionsHandler());
        MinecraftForge.EVENT_BUS.register(new Restart());
        MinecraftForge.EVENT_BUS.register(new TaskScheduler());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        LOGGER.info("Forge Announcements mod has been enabled.");
        LOGGER.info("=========================");
        LOGGER.info("Paradigm");
        LOGGER.info("Version: " + VERSION);
        LOGGER.info("Author: Avalanche7CZ");
        LOGGER.info("Discord: https://discord.com/invite/qZDcQdEFqQ");
        LOGGER.info("=========================");

        UpdateChecker.checkForUpdates();
        Announcements.onServerStarting(event);
        Restart.onServerStarting(event);
        PermissionsHandler.onServerStarting(event);
        AnnouncementsCommand.registerCommands(event);
    }

    public static class UpdateChecker {

        private static final Logger LOGGER = LogManager.getLogger(MODID);
        private static final String LATEST_VERSION_URL = "https://raw.githubusercontent.com/Avalanche7CZ/ForgeAnnouncements/1.12.2/version.txt";
        private static String CURRENT_VERSION = VERSION;

        public static void checkForUpdates() {
            try {
                URL url = new URL(LATEST_VERSION_URL);
                BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
                String latestVersion = reader.readLine();
                reader.close();

                if (!CURRENT_VERSION.equals(latestVersion)) {
                    LOGGER.info("A new version of the mod is available: " + latestVersion);
                } else {
                    LOGGER.info("You are running the latest version of the mod: " + CURRENT_VERSION);
                }
            } catch (Exception e) {
                LOGGER.info("Failed to check for updates.");
            }
        }
    }
}
