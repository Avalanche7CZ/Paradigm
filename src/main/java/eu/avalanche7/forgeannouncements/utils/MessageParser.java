package eu.avalanche7.forgeannouncements.utils;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.concurrent.ConcurrentHashMap;

public class MessageParser {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern URL_PATTERN = Pattern.compile("http://\\S+|https://\\S+");

    private static final Map<Pattern, BiConsumer<Matcher, TagContext>> TAG_HANDLERS = Map.of(
            Pattern.compile("\\[link=(.*?)\\]"), (matcher, context) -> {
                String url = matcher.group(1);
                context.component.append(new TextComponent(url)
                                .setStyle(context.style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)))))
                        .append(new TextComponent(" "));
            },
            Pattern.compile("\\[command=(.*?)\\]"), (matcher, context) -> {
                String command = matcher.group(1);
                context.component.append(new TextComponent("/" + command + " ")
                        .setStyle(context.style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + command))));
            },
            Pattern.compile("\\[hover=(.*?)\\]"), (matcher, context) -> {
                String hoverText = matcher.group(1);
                context.component.append(new TextComponent(hoverText+ " ")
                        .setStyle(context.style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponent(hoverText)))));
            },
            Pattern.compile("\\[divider\\]"), (matcher, context) -> {
                context.component.append(new TextComponent("--------------------")
                        .setStyle(context.style.withColor(TextColor.fromLegacyFormat(ChatFormatting.GRAY))));
            },
            Pattern.compile("\\[title=(.*?)\\]"), (matcher, context) -> {
                if (context.player != null) {
                    String titleText = matcher.group(1);
                    Component titleComponent = parseMessage(context.colorCode + titleText, context.player);
                    ClientboundSetTitleTextPacket titlePacket = new ClientboundSetTitleTextPacket(titleComponent);
                    context.player.connection.send(titlePacket);
                }
            },
            Pattern.compile("\\[subtitle=(.*?)\\]"), (matcher, context) -> {
                if (context.player != null) {
                    String subtitleText = matcher.group(1);
                    Component subtitleComponent = parseMessage(context.colorCode + subtitleText, context.player);
                    ClientboundSetSubtitleTextPacket subtitlePacket = new ClientboundSetSubtitleTextPacket(subtitleComponent);
                    context.player.connection.send(subtitlePacket);
                }
            },
            Pattern.compile("\\[center\\](.*?)\\[/center\\]"), (matcher, context) -> {
                String text = matcher.group(1);
                int originalLength = text.length();
                MutableComponent centered = parseMessage(context.colorCode + text, context.player);
                String parsedText = centered.getString();
                int width = 50;
                String paddedText = centerText(parsedText, width, originalLength);
                context.component.append(new TextComponent(paddedText).setStyle(context.style));
            }
    );

    private static final Map<String, MutableComponent> messageCache = new ConcurrentHashMap<>();

    public static MutableComponent parseMessage(String rawMessage, ServerPlayer player) {
        if (rawMessage == null) {
            return new TextComponent("");
        }

        if (messageCache.containsKey(rawMessage)) {
            return messageCache.get(rawMessage);
        }

        rawMessage = rawMessage.replace("&", "§");
        Matcher hexMatcher = HEX_PATTERN.matcher(rawMessage);
        StringBuffer sb = new StringBuffer();
        while (hexMatcher.find()) {
            String hexColor = hexMatcher.group(1);
            if (isValidHexColor(hexColor)) {
                hexMatcher.appendReplacement(sb, "§#" + hexColor);
            }
        }
        hexMatcher.appendTail(sb);
        rawMessage = sb.toString();

        MutableComponent message = new TextComponent("");
        String[] parts = rawMessage.split("§", -1);
        Style currentStyle = Style.EMPTY;

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;

            if (i > 0) {
                if (parts[i].startsWith("#")) {
                    String hexCode = parts[i].substring(1, 7);
                    if (isValidHexColor(hexCode)) {
                        try {
                            currentStyle = currentStyle.withColor(TextColor.fromRgb(Integer.parseInt(hexCode, 16)));
                        } catch (NumberFormatException e) {
                            currentStyle = currentStyle.withColor(TextColor.fromRgb(0xFFFFFF));
                        }
                        parts[i] = parts[i].substring(7);
                    }
                } else {
                    char colorCode = parts[i].charAt(0);
                    ChatFormatting format = ChatFormatting.getByCode(colorCode);
                    if (format != null) {
                        currentStyle = currentStyle.applyFormat(format);
                    }
                    parts[i] = parts[i].substring(1);
                }
            }

            TagContext context = new TagContext(message, currentStyle, player, "");
            String textPart = parts[i];

            while (!textPart.isEmpty()) {
                boolean matched = false;

                for (Map.Entry<Pattern, BiConsumer<Matcher, TagContext>> entry : TAG_HANDLERS.entrySet()) {
                    Matcher matcher = entry.getKey().matcher(textPart);
                    if (matcher.find()) {
                        entry.getValue().accept(matcher, context);
                        textPart = textPart.substring(matcher.end()).trim();
                        matched = true;
                        break;
                    }
                }

                if (!matched) {
                    Matcher urlMatcher = URL_PATTERN.matcher(textPart);
                    if (urlMatcher.find()) {
                        int start = urlMatcher.start();
                        int end = urlMatcher.end();

                        if (start > 0) {
                            message.append(new TextComponent(textPart.substring(0, start)).setStyle(currentStyle));
                        }

                        String url = textPart.substring(start, end);
                        message.append(new TextComponent(url).setStyle(currentStyle.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))));

                        textPart = textPart.substring(end).trim();
                    } else {
                        message.append(new TextComponent(textPart).setStyle(currentStyle));
                        textPart = "";
                    }
                }
            }
        }
        messageCache.put(rawMessage, message);

        return message;
    }

    private static String formatUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "http://" + url;
        }
        return url;
    }

    private static boolean isValidHexColor(String hexColor) {
        return hexColor.matches("[A-Fa-f0-9]{6}");
    }

    private static String centerText(String text, int width, int originalLength) {
        int textLength = originalLength;
        if (textLength >= width) {
            return text;
        }

        int spaces = (width - textLength) / 2;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spaces; i++) {
            sb.append(" ");
        }
        sb.append(text);
        return sb.toString();
    }

    private static class TagContext {
        final MutableComponent component;
        final Style style;
        final ServerPlayer player;
        final String colorCode;

        TagContext(MutableComponent component, Style style, ServerPlayer player, String colorCode) {
            this.component = component;
            this.style = style;
            this.player = player;
            this.colorCode = colorCode;
        }
    }
}
