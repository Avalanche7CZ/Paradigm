package eu.avalanche7.paradigm.modules.discord;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.avalanche7.paradigm.utils.LiteralPlaceholders;
import eu.avalanche7.paradigm.utils.formatting.ComponentSlots;

public final class DiscordSanitizer {
    public static final int MAX_MINECRAFT_VALUE_LENGTH = 512;
    public static final int MAX_DISCORD_CONTENT_LENGTH = 1900;

    private static final Pattern SECTION_CODE = Pattern.compile("§#[A-Fa-f0-9]{6}|§[0-9a-fA-Fk-oK-OrR]|§.");
    private static final Pattern AMPERSAND_HEX = Pattern.compile("&#[A-Fa-f0-9]{6}");
    private static final Pattern AMPERSAND_CODE = Pattern.compile("&[0-9a-fA-Fk-oK-OrR]");
    private static final Pattern MARKUP_TAG = Pattern.compile("</?[A-Za-z][^<>]{0,96}>");
    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d{5,25})>");
    private static final Pattern ROLE_MENTION = Pattern.compile("<@&(\\d{5,25})>");
    private static final Pattern CHANNEL_MENTION = Pattern.compile("<#(\\d{5,25})>");
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:([A-Za-z0-9_]{1,32}):\\d{5,25}>");

    private static final String ZERO_WIDTH_SPACE = "​";

    private DiscordSanitizer() {
    }

    public static String stripMinecraftMarkup(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = ComponentSlots.strip(text);
        result = SECTION_CODE.matcher(result).replaceAll("");
        result = AMPERSAND_HEX.matcher(result).replaceAll("");
        result = AMPERSAND_CODE.matcher(result).replaceAll("");
        result = MARKUP_TAG.matcher(result).replaceAll("");
        return result;
    }

    public static String forDiscord(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String result = ComponentSlots.strip(value);
        result = SECTION_CODE.matcher(result).replaceAll("");
        result = collapseWhitespace(result);
        result = escapeMarkdown(result);
        result = defuseMassMentions(result);
        return truncate(result, MAX_DISCORD_CONTENT_LENGTH);
    }

    public static String escapeMarkdown(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' || current == '*' || current == '_' || current == '~'
                    || current == '`' || current == '|' || current == '>') {
                builder.append('\\');
            }
            builder.append(current);
        }
        return builder.toString();
    }

    public static String defuseMassMentions(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("@everyone", "@" + ZERO_WIDTH_SPACE + "everyone")
                .replace("@here", "@" + ZERO_WIDTH_SPACE + "here");
    }

    public static String forMinecraft(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String result = ComponentSlots.strip(value);
        result = result.replace("§", "");
        result = collapseWhitespace(result);
        result = result.replace('{', '(').replace('}', ')');
        result = LiteralPlaceholders.escape(result);
        return truncate(result, MAX_MINECRAFT_VALUE_LENGTH);
    }

    public static String resolveMentions(String content, Map<String, String> mentionNames) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        Map<String, String> names = mentionNames != null ? mentionNames : Map.of();

        String result = replaceAll(USER_MENTION, content, matcher -> {
            String name = names.get(matcher.group(1));
            return "@" + (name != null && !name.isBlank() ? name : "unknown");
        });
        result = replaceAll(ROLE_MENTION, result, matcher -> "@role");
        result = replaceAll(CHANNEL_MENTION, result, matcher -> "#channel");
        result = replaceAll(CUSTOM_EMOJI, result, matcher -> ":" + matcher.group(1) + ":");
        return result;
    }

    public static String describeAttachments(String content, List<String> attachmentUrls, boolean showAttachments) {
        String base = content != null ? content : "";
        if (attachmentUrls == null || attachmentUrls.isEmpty()) {
            return base;
        }
        if (showAttachments) {
            return base;
        }
        String marker = attachmentUrls.size() == 1
                ? "[attachment]"
                : "[" + attachmentUrls.size() + " attachments]";
        return base.isBlank() ? marker : base + " " + marker;
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        if (max <= 1) {
            return value.substring(0, Math.max(0, max));
        }
        return value.substring(0, max - 1) + "…";
    }

    private static String collapseWhitespace(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean previousWasSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean whitespace = current == '\n' || current == '\r' || current == '\t' || current == ' '
                    || Character.isISOControl(current);
            if (whitespace) {
                if (!previousWasSpace && builder.length() > 0) {
                    builder.append(' ');
                }
                previousWasSpace = true;
            } else {
                builder.append(current);
                previousWasSpace = false;
            }
        }
        return builder.toString().trim();
    }

    private interface Replacement {
        String apply(Matcher matcher);
    }

    private static String replaceAll(Pattern pattern, String input, Replacement replacement) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        StringBuilder builder = new StringBuilder(input.length());
        int cursor = 0;
        do {
            builder.append(input, cursor, matcher.start()).append(replacement.apply(matcher));
            cursor = matcher.end();
        } while (matcher.find());
        builder.append(input, cursor, input.length());
        return builder.toString();
    }
}
