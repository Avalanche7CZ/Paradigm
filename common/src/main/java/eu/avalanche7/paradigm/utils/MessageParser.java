package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingParser;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageParser {
    private final Map<String, IComponent> messageCache = new ConcurrentHashMap<>();
    private final Placeholders placeholders;
    private final IPlatformAdapter platformAdapter;
    private final FormattingParser formattingParser;

    public MessageParser(Placeholders placeholders, IPlatformAdapter platformAdapter) {
        this.placeholders = placeholders;
        this.platformAdapter = platformAdapter;
        this.formattingParser = new FormattingParser(platformAdapter, placeholders);
    }

    public IComponent parseMessage(String rawMessage, IPlayer player) {
        if (rawMessage == null) {
            return platformAdapter.createLiteralComponent("");
        }

        final boolean cacheable = !rawMessage.contains("{");
        final String cacheKey = rawMessage + "_player_" + (player != null ? player.getUUID() : "null");
        if (cacheable && messageCache.containsKey(cacheKey)) {
            return messageCache.get(cacheKey).copy();
        }

        IComponent parsed = parseTagBasedMessage(rawMessage, player);
        if (cacheable) {
            messageCache.put(cacheKey, parsed);
        }
        return parsed.copy();
    }

    private IComponent parseTagBasedMessage(String rawMessage, IPlayer player) {
        String processedMessage = platformAdapter.replacePlaceholders(rawMessage, player);
        processedMessage = convertLegacyToNewFormat(processedMessage);
        return formattingParser.parse(processedMessage, player);
    }

    private String convertLegacyToNewFormat(String text) {
        text = text.replace("&", "§");

        Pattern hexColorPattern = Pattern.compile("§#([A-Fa-f0-9]{6})");
        Matcher hexMatcher = hexColorPattern.matcher(text);
        StringBuilder hexResult = new StringBuilder();
        while (hexMatcher.find()) {
            String hexColor = hexMatcher.group(1);
            hexMatcher.appendReplacement(hexResult, String.format("<color:#%s>", hexColor));
        }
        hexMatcher.appendTail(hexResult);
        text = hexResult.toString();

        Pattern legacyPattern = Pattern.compile("§([0-9a-fA-Fk-oK-OrR])");
        Matcher matcher = legacyPattern.matcher(text);

        StringBuilder result = new StringBuilder();
        boolean hasBold = false;
        boolean hasItalic = false;
        boolean hasUnderline = false;
        boolean hasStrikethrough = false;
        boolean hasObfuscated = false;
        boolean hasColor = false;

        while (matcher.find()) {
            String code = matcher.group(1);
            char c = Character.toLowerCase(code.charAt(0));

            if (c == 'r') {
                StringBuilder closeTags = new StringBuilder();
                if (hasObfuscated) closeTags.append("</obfuscated>");
                if (hasStrikethrough) closeTags.append("</strikethrough>");
                if (hasUnderline) closeTags.append("</underline>");
                if (hasItalic) closeTags.append("</italic>");
                if (hasBold) closeTags.append("</bold>");
                if (hasColor) closeTags.append("</color>");

                matcher.appendReplacement(result, closeTags + "<reset>");
                hasBold = hasItalic = hasUnderline = hasStrikethrough = hasObfuscated = hasColor = false;
                continue;
            }

            Integer rgb = legacyColorRgb(c);
            if (rgb != null) {
                String replacement = (hasColor ? "</color>" : "") + String.format("<color:#%06X>", rgb);
                matcher.appendReplacement(result, replacement);
                hasColor = true;
                continue;
            }

            switch (c) {
                case 'l' -> { matcher.appendReplacement(result, "<bold>"); hasBold = true; }
                case 'o' -> { matcher.appendReplacement(result, "<italic>"); hasItalic = true; }
                case 'n' -> { matcher.appendReplacement(result, "<underline>"); hasUnderline = true; }
                case 'm' -> { matcher.appendReplacement(result, "<strikethrough>"); hasStrikethrough = true; }
                case 'k' -> { matcher.appendReplacement(result, "<obfuscated>"); hasObfuscated = true; }
                default -> {
                    // unknown code, ignore
                }
            }
        }
        matcher.appendTail(result);

        StringBuilder closeTags = new StringBuilder();
        if (hasObfuscated) closeTags.append("</obfuscated>");
        if (hasStrikethrough) closeTags.append("</strikethrough>");
        if (hasUnderline) closeTags.append("</underline>");
        if (hasItalic) closeTags.append("</italic>");
        if (hasBold) closeTags.append("</bold>");
        if (hasColor) closeTags.append("</color>");
        result.append(closeTags);

        return result.toString();
    }

    private static Integer legacyColorRgb(char c) {
        return switch (c) {
            case '0' -> 0x000000;
            case '1' -> 0x0000AA;
            case '2' -> 0x00AA00;
            case '3' -> 0x00AAAA;
            case '4' -> 0xAA0000;
            case '5' -> 0xAA00AA;
            case '6' -> 0xFFAA00;
            case '7' -> 0xAAAAAA;
            case '8' -> 0x555555;
            case '9' -> 0x5555FF;
            case 'a' -> 0x55FF55;
            case 'b' -> 0x55FFFF;
            case 'c' -> 0xFF5555;
            case 'd' -> 0xFF55FF;
            case 'e' -> 0xFFFF55;
            case 'f' -> 0xFFFFFF;
            default -> null;
        };
    }

    public FormattingParser getFormattingParser() {
        return formattingParser;
    }

    public void clearCache() {
        messageCache.clear();
    }
}
