package eu.avalanche7.paradigm.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class EmojiConfigHandler {
    public static final EmojiConfigHandler CONFIG = new EmojiConfigHandler();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final Path configFilePath = Path.of("config", "paradigm", "emojis.json");
    private Map<String, String> emojis = new HashMap<>();

    public void loadEmojis() {
        try {
            Files.createDirectories(configFilePath.getParent());

            if (Files.exists(configFilePath)) {
                try (Reader reader = Files.newBufferedReader(configFilePath, StandardCharsets.UTF_8)) {
                    Config config = gson.fromJson(reader, Config.class);
                    if (config != null && config.emojis != null) {
                        emojis = config.emojis;
                        System.out.println("[Paradigm] Loaded " + emojis.size() + " emojis from config");
                    }
                }
            } else {
                generateDefaultConfig();
            }
        } catch (IOException e) {
            System.err.println("[Paradigm] Error loading emojis config: " + e.getMessage());
            e.printStackTrace();
            generateDefaultEmojis();
        }
    }

    private void generateDefaultConfig() {
        try {
            Config defaultConfig = new Config();
            defaultConfig.emojis = getDefaultEmojis();

            try (Writer writer = Files.newBufferedWriter(configFilePath, StandardCharsets.UTF_8)) {
                gson.toJson(defaultConfig, writer);
            }

            emojis = defaultConfig.emojis;
            System.out.println("[Paradigm] Generated default emojis config with " + emojis.size() + " emojis");
        } catch (IOException e) {
            System.err.println("[Paradigm] Error creating emojis config: " + e.getMessage());
            generateDefaultEmojis();
        }
    }

    private void generateDefaultEmojis() {
        emojis = getDefaultEmojis();
    }

    private Map<String, String> getDefaultEmojis() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("smile", "😊");
        defaults.put("laugh", "😂");
        defaults.put("heart", "❤");
        defaults.put("star", "⭐");
        defaults.put("fire", "🔥");
        defaults.put("sun", "☀");
        defaults.put("moon", "🌙");
        defaults.put("cloud", "☁");
        defaults.put("check", "✓");
        defaults.put("cross", "✗");
        defaults.put("checkmark", "✓");
        defaults.put("x", "✗");
        defaults.put("arrow_right", "→");
        defaults.put("arrow_left", "←");
        defaults.put("arrow_up", "↑");
        defaults.put("arrow_down", "↓");
        defaults.put("skull", "💀");
        defaults.put("diamond", "◆");
        defaults.put("square", "■");
        defaults.put("circle", "●");
        defaults.put("triangle", "▲");
        defaults.put("music", "♪");
        defaults.put("bell", "🔔");
        defaults.put("warning", "⚠");
        defaults.put("info", "ℹ");
        defaults.put("sword", "⚔");
        defaults.put("shield", "🛡");
        defaults.put("crown", "👑");
        defaults.put("cake", "🎂");
        defaults.put("gift", "🎁");
        defaults.put("trophy", "🏆");
        defaults.put("medal", "🎖");
        defaults.put("sparkles", "✨");
        defaults.put("boom", "💥");
        defaults.put("snow", "❄");
        defaults.put("plus", "✚");
        defaults.put("minus", "➖");
        defaults.put("equals", "=");
        defaults.put("target", "◯");
        defaults.put("hourglass", "⏳");
        defaults.put("stopwatch", "⏱");
        defaults.put("bolt", "⚡");
        defaults.put("droplet", "💧");
        defaults.put("leaf", "🍃");
        defaults.put("flower", "🌸");
        defaults.put("herb", "🌿");
        defaults.put("gem", "💎");
        defaults.put("hourglass_flip", "⌛");
        defaults.put("hexagon", "⬡");
        defaults.put("infinity", "∞");
        defaults.put("link", "🔗");
        defaults.put("palette", "🎨");
        return defaults;
    }

    public String getEmoji(String name) {
        return emojis.getOrDefault(name.toLowerCase(), "");
    }

    public Map<String, String> getAllEmojis() {
        return new HashMap<>(emojis);
    }

    public void addEmoji(String name, String emoji) {
        emojis.put(name.toLowerCase(), emoji);
    }

    public void removeEmoji(String name) {
        emojis.remove(name.toLowerCase());
    }

    public static class Config {
        public Map<String, String> emojis = new HashMap<>();
    }
}

