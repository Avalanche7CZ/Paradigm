package eu.avalanche7.forgeannouncements;

import eu.avalanche7.forgeannouncements.chat.StaffChat;
import eu.avalanche7.forgeannouncements.commands.GroupChatCommands;
import eu.avalanche7.forgeannouncements.configs.*;
import eu.avalanche7.forgeannouncements.listeners.GroupChatListener;
import eu.avalanche7.forgeannouncements.utils.*;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod("forgeannouncements")
public class ForgeAnnouncements {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final GroupChatManager groupChatManager = new GroupChatManager();

    public ForgeAnnouncements() {
        LOGGER.info("Initializing Forge Announcement mod...");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(Mentions.class);
        MinecraftForge.EVENT_BUS.register(PermissionsHandler.class);
        MinecraftForge.EVENT_BUS.register(RestartConfigHandler.class);
        MinecraftForge.EVENT_BUS.register(Restart.class);
        MinecraftForge.EVENT_BUS.register(StaffChat.class);
        MinecraftForge.EVENT_BUS.register(Lang.class);
        MinecraftForge.EVENT_BUS.register(CommandManager.class);
        MinecraftForge.EVENT_BUS.register(GroupChatManager.class);
        MinecraftForge.EVENT_BUS.register(GroupChatListener.class);
        MinecraftForge.EVENT_BUS.register(new GroupChatListener(groupChatManager));
        GroupChatCommands.setManager(groupChatManager);

        try {
            createDefaultConfigs();
            createLangFolder();
            createCommandFolder();

            Path serverConfigDir = FMLPaths.GAMEDIR.get().resolve("config/forgeannouncements");

            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MainConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("main.toml").toString());
            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, AnnouncementsConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("announcements.toml").toString());
            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MentionConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("mentions.toml").toString());
            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, RestartConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("restarts.toml").toString());
            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ChatConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("chat.toml").toString());

            MOTDConfigHandler.loadConfig();
            CMConfig.loadCommands();
            MainConfigHandler.loadConfig(MainConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("main.toml").toString());
            RestartConfigHandler.loadConfig(RestartConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("restarts.toml").toString());
            AnnouncementsConfigHandler.loadConfig(AnnouncementsConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("announcements.toml").toString());
            MentionConfigHandler.loadConfig(MentionConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("mentions.toml").toString());
            ChatConfigHandler.loadConfig(ChatConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("chat.toml").toString());

            Lang.initializeLanguage();

        } catch (Exception e) {
            LOGGER.error("Failed to register or load configuration", e);
            throw new RuntimeException("Configuration loading failed", e);
        }
    }

    private void createDefaultConfigs() throws IOException {
        Path configDir = FMLPaths.GAMEDIR.get().resolve("config/forgeannouncements");
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }

        Path mainConfig = configDir.resolve("main.toml");
        if (!Files.exists(mainConfig)) {
            Files.createFile(mainConfig);
            MainConfigHandler.loadConfig(MainConfigHandler.SERVER_CONFIG, mainConfig.toString());
            MainConfigHandler.SERVER_CONFIG.save();
        }

        Path announcementsConfig = configDir.resolve("announcements.toml");
        if (!Files.exists(announcementsConfig)) {
            Files.createFile(announcementsConfig);
            AnnouncementsConfigHandler.loadConfig(AnnouncementsConfigHandler.SERVER_CONFIG, announcementsConfig.toString());
            AnnouncementsConfigHandler.SERVER_CONFIG.save();
        }

        Path motdConfigPath = configDir.resolve("motd.json");
        if (!Files.exists(motdConfigPath)) {
            MOTDConfigHandler.saveConfig();
        } else {
            MOTDConfigHandler.loadConfig();
        }

        Path commandsDir = configDir.resolve("commands");
        if (!Files.exists(commandsDir)) {
            Files.createDirectories(commandsDir);
        }

        Path commandsConfig = commandsDir.resolve("example.json");
        if (!Files.exists(commandsConfig)) {
            Files.createFile(commandsConfig);
            CMConfig.loadCommands();
        }

        Path mentionsConfig = configDir.resolve("mentions.toml");
        if (!Files.exists(mentionsConfig)) {
            Files.createFile(mentionsConfig);
            MentionConfigHandler.loadConfig(MentionConfigHandler.SERVER_CONFIG, mentionsConfig.toString());
            MentionConfigHandler.SERVER_CONFIG.save();
        }
        Path restartConfig = configDir.resolve("restarts.toml");
        if (!Files.exists(restartConfig)) {
            Files.createFile(restartConfig);
            RestartConfigHandler.loadConfig(RestartConfigHandler.SERVER_CONFIG, restartConfig.toString());
            RestartConfigHandler.SERVER_CONFIG.save();
        }
        Path chatConfig = configDir.resolve("chat.toml");
        if (!Files.exists(chatConfig)) {
            Files.createFile(chatConfig);
            ChatConfigHandler.loadConfig(ChatConfigHandler.SERVER_CONFIG, chatConfig.toString());
            ChatConfigHandler.SERVER_CONFIG.save();
        }

    }

    private void createCommandFolder() throws IOException {
        Path commandsFolder = FMLPaths.GAMEDIR.get().resolve("config/forgeannouncements/commands");
        if (!Files.exists(commandsFolder)) {
            Files.createDirectories(commandsFolder);
        }
    }

    private void createLangFolder() throws IOException {
        Path langFolder = FMLPaths.GAMEDIR.get().resolve("config/forgeannouncements/lang");
        if (!Files.exists(langFolder)) {
            Files.createDirectories(langFolder);
        }
        Lang.ensureDefaultLangFile();
    }

    private void setup(final FMLCommonSetupEvent event) {
        String version = ModLoadingContext.get().getActiveContainer().getModInfo().getVersion().toString();
        String displayName = ModLoadingContext.get().getActiveContainer().getModInfo().getDisplayName();

        LOGGER.info("Forge Announcements mod has been enabled.");
        LOGGER.info("=========================");
        LOGGER.info(displayName);
        LOGGER.info("Version " + version);
        LOGGER.info("Author: Avalanche7CZ");
        LOGGER.info("Discord: https://discord.com/invite/qZDcQdEFqQ");
        LOGGER.info("=========================");
        UpdateChecker.checkForUpdates();
    }

    public static class UpdateChecker {

        private static final String LATEST_VERSION_URL = "https://raw.githubusercontent.com/Avalanche7CZ/ForgeAnnouncements/main/version.txt";
        private static String CURRENT_VERSION;

        public static void checkForUpdates() {
            try {
                CURRENT_VERSION = ModLoadingContext.get().getActiveContainer().getModInfo().getVersion().toString();

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
