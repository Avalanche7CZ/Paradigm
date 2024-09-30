package eu.avalanche7.forgeannouncements.configs;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import org.apache.commons.lang3.tuple.Pair;

@Mod.EventBusSubscriber(modid = "forgeannouncements", bus = Mod.EventBusSubscriber.Bus.MOD)
public class ChatConfigHandler {

    public static ForgeConfigSpec SERVER_CONFIG;
    public static final ChatConfigHandler.Config CONFIG;

    static {
        final Pair<ChatConfigHandler.Config, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(ChatConfigHandler.Config::new);
        SERVER_CONFIG = specPair.getRight();
        CONFIG = specPair.getLeft();
    }

    public static class Config {
        public final ForgeConfigSpec.BooleanValue enableStaffChat;
        public final ForgeConfigSpec.ConfigValue<String> staffChatFormat;
        public final ForgeConfigSpec.BooleanValue enableStaffBossBar;

        public Config(ForgeConfigSpec.Builder builder) {

            builder.comment("Chat Module Settings")
                    .push("Chat");

            builder.comment("Staff Chat Settings")
                    .push("staff_chat");

            enableStaffChat = builder
                    .comment("Enable staff chat feature")
                    .define("enableStaffChat", true);

            staffChatFormat = builder
                    .comment("Format for staff chat messages")
                    .define("staffChatFormat", "§f[§cStaff Chat§f] §d%s §7> §f%s");

            enableStaffBossBar = builder
                    .comment("Enable boss bar while staff chat is enabled")
                    .define("enableStaffBossBar", true);

            builder.pop();
        }
    }

    public static void loadConfig(ForgeConfigSpec config, String path) {
        final CommentedFileConfig file = CommentedFileConfig.builder(path)
                .sync()
                .autosave()
                .writingMode(com.electronwill.nightconfig.core.io.WritingMode.REPLACE)
                .build();
        file.load();
        config.setConfig(file);
    }
}