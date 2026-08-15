package eu.avalanche7.paradigm.core;

import java.util.Objects;

import org.slf4j.Logger;

import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Lang;
import eu.avalanche7.paradigm.utils.MessageParser;

public final class PlayerMessenger {

    private final Lang lang;
    private final MessageParser parser;
    private final IPlatformAdapter platform;
    private final Logger logger;
    private final DebugLogger debugLogger;

    public PlayerMessenger(Lang lang, MessageParser parser, IPlatformAdapter platform,
                           Logger logger, DebugLogger debugLogger) {
        this.lang = lang;
        this.parser = parser;
        this.platform = Objects.requireNonNull(platform, "platform");
        this.logger = logger;
        this.debugLogger = debugLogger;
    }

    public String translate(String key, String fallback) {
        if (lang == null || key == null) {
            return fallback;
        }
        String raw = lang.getTranslation(key);
        return raw == null || raw.equals(key) ? fallback : raw;
    }

    public void sendDecorated(IPlayer player, String header, String key, String fallback, String... placeholders) {
        if (player == null) {
            return;
        }
        String raw = applyPlaceholders(translate(key, fallback), placeholders);
        String decorated = "<color:#22D3EE><bold>[" + header + "]</bold></color> <color:#E5E7EB>" + raw + "</color>";
        send(player, decorated);
    }

    public void send(IPlayer player, String formatted) {
        if (player == null || parser == null) {
            return;
        }
        try {
            platform.sendSystemMessage(player, parser.parseMessage(formatted, player));
        } catch (RuntimeException failure) {
            if (logger != null) {
                logger.warn("[Paradigm] Messaging: failed to parse or send a system message to player {}.",
                        player.getName(), failure);
            }
            if (debugLogger != null) {
                debugLogger.debugLog("Failed to send command message to player: " + failure);
            }
        }
    }

    public void logToConsole(String header, String fallback, String... placeholders) {
        if (logger == null) {
            return;
        }
        logger.info("[Paradigm {}] {}", header, applyPlaceholders(fallback, placeholders));
    }

    public static String applyPlaceholders(String raw, String... placeholders) {
        if (raw == null || placeholders == null) {
            return raw;
        }
        String result = raw;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            result = result.replace(placeholders[i], placeholders[i + 1]);
        }
        return result;
    }
}
