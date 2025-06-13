package eu.avalanche7.paradigm.utils;

import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageParser {

    private final Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private final Pattern urlPattern = Pattern.compile("https?://\\S+");
    private final Map<Pattern, BiConsumer<Matcher, TagContext>> tagHandlers;
    private final Map<String, MutableText> messageCache = new ConcurrentHashMap<>();
    private final Placeholders placeholders;

    public MessageParser(Placeholders placeholders) {
        this.placeholders = placeholders;
        this.tagHandlers = new LinkedHashMap<>();
        initializeTagHandlers();
    }

    private void initializeTagHandlers() {
        tagHandlers.put(Pattern.compile("\\[link=(.*?)\\]"), (matcher, context) -> {
            String url = matcher.group(1);
            context.getText().append(Text.literal(url)
                            .setStyle(context.getCurrentStyle().withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)))))
                    .append(Text.literal(" ").setStyle(context.getCurrentStyle()));
        });
        tagHandlers.put(Pattern.compile("\\[command=(.*?)\\]"), (matcher, context) -> {
            String command = matcher.group(1);
            String fullCommand = command.startsWith("/") ? command : "/" + command;
            context.getText().append(Text.literal(fullCommand)
                            .setStyle(context.getCurrentStyle().withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, fullCommand))))
                    .append(Text.literal(" ").setStyle(context.getCurrentStyle()));
        });
        tagHandlers.put(Pattern.compile("\\[hover=(.*?)\\](.*?)\\[/hover\\]", Pattern.DOTALL), (matcher, context) -> {
            String hoverTextContent = matcher.group(1);
            String mainTextContent = matcher.group(2);
            MutableText hoverComponent = parseMessageInternal(hoverTextContent, context.getPlayer(), Style.EMPTY);
            MutableText textWithHover = Text.literal("");
            parseTextRecursive(mainTextContent, textWithHover, context.getCurrentStyle(), context.getPlayer());
            applyHoverToComponent(textWithHover, new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverComponent));
            context.getText().append(textWithHover);
        });
        tagHandlers.put(Pattern.compile("\\[divider\\]"), (matcher, context) -> {
            context.getText().append(Text.literal("--------------------")
                    .setStyle(context.getCurrentStyle().withColor(TextColor.fromFormatting(Formatting.GRAY))));
        });
        tagHandlers.put(Pattern.compile("\\[title=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (context.getPlayer() != null) {
                String titleText = matcher.group(1);
                MutableText titleComponent = parseTitleOrSubtitle(titleText, context.getCurrentStyle(), context.getPlayer());
                context.getPlayer().networkHandler.sendPacket(new ClearTitleS2CPacket(false));
                context.getPlayer().networkHandler.sendPacket(new TitleS2CPacket(titleComponent));
            }
        });
        tagHandlers.put(Pattern.compile("\\[subtitle=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (context.getPlayer() != null) {
                String subtitleText = matcher.group(1);
                MutableText subtitleComponent = parseTitleOrSubtitle(subtitleText, context.getCurrentStyle(), context.getPlayer());
                context.getPlayer().networkHandler.sendPacket(new SubtitleS2CPacket(subtitleComponent));
            }
        });
        tagHandlers.put(Pattern.compile("\\[center\\](.*?)\\[/center\\]", Pattern.DOTALL), (matcher, context) -> {
            String textToCenter = matcher.group(1);
            MutableText innerComponent = parseMessageInternal(textToCenter, context.getPlayer(), context.getCurrentStyle());
            String plainInnerText = innerComponent.getString();
            int approximateChatWidthChars = 53;
            String paddingSpaces = "";
            int textLength = plainInnerText.length();

            if (textLength < approximateChatWidthChars) {
                int totalPadding = approximateChatWidthChars - textLength;
                int leftPadding = totalPadding / 2;
                if (leftPadding > 0) {
                    paddingSpaces = " ".repeat(leftPadding);
                }
            }
            if (!paddingSpaces.isEmpty()) {
                context.getText().append(Text.literal(paddingSpaces).setStyle(context.getCurrentStyle()));
            }
            context.getText().append(innerComponent);
        });
    }

    private void applyHoverToComponent(MutableText component, HoverEvent hoverEvent) {
        component.setStyle(component.getStyle().withHoverEvent(hoverEvent));
        for (Text sibling : component.getSiblings()) {
            if (sibling instanceof MutableText mutableSibling) {
                applyHoverToComponent(mutableSibling, hoverEvent);
            }
        }
    }

    public MutableText parseMessage(String rawMessage, ServerPlayerEntity player) {
        return parseMessageInternal(rawMessage, player, Style.EMPTY);
    }

    private MutableText parseMessageInternal(String rawMessage, ServerPlayerEntity player, Style initialStyle) {
        if (rawMessage == null) {
            return Text.literal("").setStyle(initialStyle);
        }

        String processedMessage = this.placeholders.replacePlaceholders(rawMessage, player);
        Matcher hexMatcher = hexPattern.matcher(processedMessage);
        StringBuilder sb = new StringBuilder();
        while (hexMatcher.find()) {
            String hexColor = hexMatcher.group(1);
            hexMatcher.appendReplacement(sb, "§#" + hexColor);
        }
        hexMatcher.appendTail(sb);
        processedMessage = sb.toString();
        String messageForParsing = processedMessage.replace("&", "§");

        final String finalCacheKey = messageForParsing + "_style_" + initialStyle.hashCode() + (player != null ? player.getUuidAsString() : "null_player");
        if (messageCache.containsKey(finalCacheKey)) {
            return messageCache.get(finalCacheKey).copy();
        }

        MutableText rootComponent = Text.literal("");
        parseTextRecursive(messageForParsing, rootComponent, initialStyle, player);

        messageCache.put(finalCacheKey, rootComponent);
        return rootComponent;
    }

    private void parseTextRecursive(String textToParse, MutableText parentComponent, Style currentStyle, ServerPlayerEntity player) {
        int currentIndex = 0;
        int length = textToParse.length();
        Matcher urlMatcher = urlPattern.matcher(textToParse);

        while (currentIndex < length) {
            int nextLegacyFormat = textToParse.indexOf('§', currentIndex);
            int nextTagStart = textToParse.indexOf('[', currentIndex);
            boolean nextUrlFound = urlMatcher.find(currentIndex);
            int nextUrlStart = nextUrlFound ? urlMatcher.start() : -1;
            int firstEventIndex = length;

            if (nextLegacyFormat != -1) firstEventIndex = Math.min(firstEventIndex, nextLegacyFormat);
            if (nextTagStart != -1) firstEventIndex = Math.min(firstEventIndex, nextTagStart);
            if (nextUrlFound) firstEventIndex = Math.min(firstEventIndex, nextUrlStart);

            if (firstEventIndex > currentIndex) {
                parentComponent.append(Text.literal(textToParse.substring(currentIndex, firstEventIndex)).setStyle(currentStyle));
            }

            currentIndex = firstEventIndex;
            if (currentIndex == length) break;

            if (nextLegacyFormat == currentIndex) {
                if (currentIndex + 1 < length) {
                    char formatChar = textToParse.charAt(currentIndex + 1);
                    if (formatChar == '#') {
                        if (currentIndex + 7 < length) {
                            String hex = textToParse.substring(currentIndex + 2, currentIndex + 8);
                            try {
                                currentStyle = currentStyle.withColor(TextColor.parse("#" + hex).getOrThrow());
                                currentIndex += 8;
                            } catch (Exception e) {
                                parentComponent.append(Text.literal(textToParse.substring(currentIndex, currentIndex + 2)).setStyle(currentStyle));
                                currentIndex += 2;
                            }
                        } else {
                            parentComponent.append(Text.literal(textToParse.substring(currentIndex, currentIndex + 1)).setStyle(currentStyle));
                            currentIndex += 1;
                        }
                    } else {
                        Formatting format = Formatting.byCode(formatChar);
                        if (format != null) {
                            currentStyle = currentStyle.withFormatting(format);
                            if (format == Formatting.RESET) currentStyle = Style.EMPTY;
                        } else {
                            parentComponent.append(Text.literal("§").setStyle(currentStyle));
                        }
                        currentIndex += 2;
                    }
                } else {
                    parentComponent.append(Text.literal("§").setStyle(currentStyle));
                    currentIndex += 1;
                }
            } else if (nextTagStart == currentIndex) {
                boolean tagHandled = false;
                for (Map.Entry<Pattern, BiConsumer<Matcher, TagContext>> entry : tagHandlers.entrySet()) {
                    Pattern tagPattern = entry.getKey();
                    Matcher tagMatcher = tagPattern.matcher(textToParse);
                    if (tagMatcher.find(currentIndex) && tagMatcher.start() == currentIndex) {
                        TagContext context = new TagContext(parentComponent, currentStyle, player);
                        entry.getValue().accept(tagMatcher, context);
                        currentIndex = tagMatcher.end();
                        tagHandled = true;
                        break;
                    }
                }
                if (!tagHandled) {
                    parentComponent.append(Text.literal("[").setStyle(currentStyle));
                    currentIndex += 1;
                }
            } else if (nextUrlFound && nextUrlStart == currentIndex) {
                String url = urlMatcher.group(0);
                Style urlStyle = currentStyle.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)));
                parentComponent.append(Text.literal(url).setStyle(urlStyle));
                currentIndex = urlMatcher.end();
            } else {
                if (currentIndex < length) {
                    parentComponent.append(Text.literal(textToParse.substring(currentIndex, currentIndex + 1)).setStyle(currentStyle));
                    currentIndex += 1;
                }
            }
        }
    }

    private MutableText parseTitleOrSubtitle(String rawText, Style baseStyle, ServerPlayerEntity player) {
        MutableText parsedComponent = parseMessageInternal(rawText, player, Style.EMPTY);
        return applyBaseStyle(parsedComponent, baseStyle);
    }

    private MutableText applyBaseStyle(MutableText component, Style baseStyle) {
        MutableText styledComponent = component.copy();
        styledComponent.setStyle(baseStyle.withParent(styledComponent.getStyle()));

        List<Text> children = new ArrayList<>(styledComponent.getSiblings());
        styledComponent.getSiblings().clear();
        for (Text child : children) {
            if (child instanceof MutableText mutableChild) {
                styledComponent.append(applyBaseStyle(mutableChild, baseStyle));
            } else {
                styledComponent.append(child.copy().setStyle(baseStyle.withParent(child.getStyle())));
            }
        }
        return styledComponent;
    }

    private String formatUrl(String url) {
        if (url != null && !url.toLowerCase().startsWith("http://") && !url.toLowerCase().startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }

    private static class TagContext {
        private final MutableText text;
        private final Style currentStyle;
        private final ServerPlayerEntity player;

        TagContext(MutableText text, Style style, ServerPlayerEntity player) {
            this.text = text;
            this.currentStyle = style;
            this.player = player;
        }

        public MutableText getText() { return text; }
        public Style getCurrentStyle() { return currentStyle; }
        public ServerPlayerEntity getPlayer() { return player; }
    }
}
