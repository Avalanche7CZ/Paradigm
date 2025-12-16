package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingParser;
import net.minecraft.ChatFormatting;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageParser {
    private final Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private final Pattern urlPattern = Pattern.compile("https?://\\S+");
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

        final String cacheKey = rawMessage + "_player_" + (player != null ? player.getUUID() : "null");
        if (messageCache.containsKey(cacheKey)) {
            return messageCache.get(cacheKey).copy();
        }

        IComponent parsed = parseTagBasedMessage(rawMessage, player);
        messageCache.put(cacheKey, parsed);
        return parsed.copy();
    }

    private IComponent parseTagBasedMessage(String rawMessage, IPlayer player) {
        String processedMessage = this.placeholders.replacePlaceholders(rawMessage, player);
        processedMessage = convertLegacyToNewFormat(processedMessage);
        IComponent component = formattingParser.parse(processedMessage, player);
        return component;
    }

    private String convertLegacyToNewFormat(String text) {
        text = text.replace("&", "§");

        Pattern hexColorPattern = Pattern.compile("§#([A-Fa-f0-9]{6})");
        Matcher hexMatcher = hexColorPattern.matcher(text);
        StringBuffer hexResult = new StringBuffer();
        while (hexMatcher.find()) {
            String hexColor = hexMatcher.group(1);
            hexMatcher.appendReplacement(hexResult, String.format("<color:#%s>", hexColor));
        }
        hexMatcher.appendTail(hexResult);
        text = hexResult.toString();

        Pattern legacyPattern = Pattern.compile("§([0-9a-fA-Fk-oK-OrR])");
        Matcher matcher = legacyPattern.matcher(text);

        StringBuffer result = new StringBuffer();
        boolean hasBold = false;
        boolean hasItalic = false;
        boolean hasUnderline = false;
        boolean hasStrikethrough = false;
        boolean hasObfuscated = false;
        boolean hasColor = false;

        while (matcher.find()) {
            String code = matcher.group(1);
            ChatFormatting format = ChatFormatting.getByCode(code.charAt(0));

            if (format != null) {
                if (format == ChatFormatting.RESET) {
                    StringBuilder closeTags = new StringBuilder();
                    if (hasObfuscated) closeTags.append("</obfuscated>");
                    if (hasStrikethrough) closeTags.append("</strikethrough>");
                    if (hasUnderline) closeTags.append("</underline>");
                    if (hasItalic) closeTags.append("</italic>");
                    if (hasBold) closeTags.append("</bold>");
                    if (hasColor) closeTags.append("</color>");

                    matcher.appendReplacement(result, closeTags.toString() + "<reset>");
                    hasBold = hasItalic = hasUnderline = hasStrikethrough = hasObfuscated = hasColor = false;
                } else if (format.isColor()) {
                    int rgb = getColorRgb(format);
                    String replacement = (hasColor ? "</color>" : "") + String.format("<color:#%06X>", rgb);
                    matcher.appendReplacement(result, replacement);
                    hasColor = true;
                } else if (format == ChatFormatting.BOLD) {
                    matcher.appendReplacement(result, "<bold>");
                    hasBold = true;
                } else if (format == ChatFormatting.ITALIC) {
                    matcher.appendReplacement(result, "<italic>");
                    hasItalic = true;
                } else if (format == ChatFormatting.UNDERLINE) {
                    matcher.appendReplacement(result, "<underline>");
                    hasUnderline = true;
                } else if (format == ChatFormatting.STRIKETHROUGH) {
                    matcher.appendReplacement(result, "<strikethrough>");
                    hasStrikethrough = true;
                } else if (format == ChatFormatting.OBFUSCATED) {
                    matcher.appendReplacement(result, "<obfuscated>");
                    hasObfuscated = true;
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

    private int getColorRgb(ChatFormatting format) {
        return switch (format) {
            case BLACK -> 0x000000;
            case DARK_BLUE -> 0x0000AA;
            case DARK_GREEN -> 0x00AA00;
            case DARK_AQUA -> 0x00AAAA;
            case DARK_RED -> 0xAA0000;
            case DARK_PURPLE -> 0xAA00AA;
            case GOLD -> 0xFFAA00;
            case GRAY -> 0xAAAAAA;
            case DARK_GRAY -> 0x555555;
            case BLUE -> 0x5555FF;
            case GREEN -> 0x55FF55;
            case AQUA -> 0x55FFFF;
            case RED -> 0xFF5555;
            case LIGHT_PURPLE -> 0xFF55FF;
            case YELLOW -> 0xFFFF55;
            case WHITE -> 0xFFFFFF;
            default -> 0xFFFFFF;
        };
    }

    public FormattingParser getFormattingParser() {
        return formattingParser;
    }

    public void clearCache() {
        messageCache.clear();
    }
}
