package eu.avalanche7.paradigm.utils;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.concurrent.ConcurrentHashMap;

public class MessageParser {

    private final Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private final Pattern urlPattern = Pattern.compile("https://?://\\S+");
    private final Map<Pattern, BiConsumer<Matcher, TagContext>> tagHandlers;
    private final Map<String, MutableComponent> messageCache = new ConcurrentHashMap<>();
    private final Placeholders placeholders;

    public MessageParser(Placeholders placeholders) {
        this.placeholders = placeholders;
        this.tagHandlers = new LinkedHashMap<>();
        initializeTagHandlers();
    }

    private void initializeTagHandlers() {
        tagHandlers.put(Pattern.compile("\\[link=(.*?)\\]"), (matcher, context) -> {
            String url = matcher.group(1);
            context.getComponent().append(new TextComponent(url)
                            .setStyle(context.getCurrentStyle().withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)))))
                    .append(new TextComponent(" ").setStyle(context.getCurrentStyle()));
        });
        tagHandlers.put(Pattern.compile("\\[command=(.*?)\\]"), (matcher, context) -> {
            String command = matcher.group(1);
            String fullCommand = command.startsWith("/") ? command : "/" + command;
            context.getComponent().append(new TextComponent(fullCommand)
                            .setStyle(context.getCurrentStyle().withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, fullCommand))))
                    .append(new TextComponent(" ").setStyle(context.getCurrentStyle()));
        });
        tagHandlers.put(Pattern.compile("\\[hover=(.*?)\\](.*?)\\[/hover\\]", Pattern.DOTALL), (matcher, context) -> {
            String hoverTextContent = matcher.group(1);
            String mainTextContent = matcher.group(2);
            MutableComponent hoverComponent = parseMessageInternal(hoverTextContent, context.getPlayer(), Style.EMPTY);
            MutableComponent textWithHover = new TextComponent("");
            parseTextRecursive(mainTextContent, textWithHover, context.getCurrentStyle(), context.getPlayer());
            applyHoverToComponent(textWithHover, new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverComponent));
            context.getComponent().append(textWithHover);
        });
        tagHandlers.put(Pattern.compile("\\[divider\\]"), (matcher, context) -> {
            context.getComponent().append(new TextComponent("--------------------")
                    .setStyle(context.getCurrentStyle().withColor(TextColor.fromLegacyFormat(ChatFormatting.GRAY))));
        });
        tagHandlers.put(Pattern.compile("\\[title=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (context.getPlayer() != null) {
                String titleText = matcher.group(1);
                MutableComponent titleComponent = parseTitleOrSubtitle(titleText, context.getCurrentStyle(), context.getPlayer());
                context.getPlayer().connection.send(new ClientboundClearTitlesPacket(true));
                context.getPlayer().connection.send(new ClientboundSetTitleTextPacket(titleComponent));
            }
        });
        tagHandlers.put(Pattern.compile("\\[subtitle=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (context.getPlayer() != null) {
                String subtitleText = matcher.group(1);
                MutableComponent subtitleComponent = parseTitleOrSubtitle(subtitleText, context.getCurrentStyle(), context.getPlayer());
                context.getPlayer().connection.send(new ClientboundSetSubtitleTextPacket(subtitleComponent));
            }
        });
        tagHandlers.put(Pattern.compile("\\[center\\](.*?)\\[/center\\]", Pattern.DOTALL), (matcher, context) -> {
            String textToCenter = matcher.group(1);
            MutableComponent innerComponent = parseMessageInternal(textToCenter, context.getPlayer(), context.getCurrentStyle());
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
                context.getComponent().append(new TextComponent(paddingSpaces).setStyle(context.getCurrentStyle()));
            }
            context.getComponent().append(innerComponent);
        });
    }

    private void applyHoverToComponent(MutableComponent component, HoverEvent hoverEvent) {
        component.setStyle(component.getStyle().withHoverEvent(hoverEvent));
        for (Component sibling : component.getSiblings()) {
            if (sibling instanceof MutableComponent) {
                applyHoverToComponent((MutableComponent) sibling, hoverEvent);
            }
        }
    }

    public MutableComponent parseMessage(String rawMessage, ServerPlayer player) {
        return parseMessageInternal(rawMessage, player, Style.EMPTY);
    }

    private MutableComponent parseMessageInternal(String rawMessage, ServerPlayer player, Style initialStyle) {
        if (rawMessage == null) {
            return new TextComponent("").setStyle(initialStyle);
        }

        String processedMessage = this.placeholders.replacePlaceholders(rawMessage, player);

        Matcher hexMatcher = hexPattern.matcher(processedMessage);
        StringBuffer sb = new StringBuffer();
        while (hexMatcher.find()) {
            String hexColor = hexMatcher.group(1);
            hexMatcher.appendReplacement(sb, "§#" + hexColor);
        }
        hexMatcher.appendTail(sb);
        processedMessage = sb.toString();

        String messageForParsing = processedMessage.replace("&", "§");

        final String finalCacheKey = messageForParsing + "_style_" + initialStyle.hashCode() + (player != null ? player.getUUID().toString() : "null_player");
        if (messageCache.containsKey(finalCacheKey)) {
            return messageCache.get(finalCacheKey).copy();
        }

        MutableComponent rootComponent = new TextComponent("");
        parseTextRecursive(messageForParsing, rootComponent, initialStyle, player);

        messageCache.put(finalCacheKey, rootComponent);
        return rootComponent;
    }

    private void parseTextRecursive(String textToParse, MutableComponent parentComponent, Style currentStyle, ServerPlayer player) {
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
            if (nextUrlFound && nextUrlStart != -1) firstEventIndex = Math.min(firstEventIndex, nextUrlStart);

            if (firstEventIndex > currentIndex) {
                parentComponent.append(new TextComponent(textToParse.substring(currentIndex, firstEventIndex)).setStyle(currentStyle));
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
                                currentStyle = currentStyle.withColor(TextColor.fromRgb(Integer.parseInt(hex, 16)));
                                currentIndex += 8;
                            } catch (NumberFormatException e) {
                                parentComponent.append(new TextComponent(textToParse.substring(currentIndex, currentIndex + 2)).setStyle(currentStyle));
                                currentIndex += 2;
                            }
                        } else {
                            parentComponent.append(new TextComponent(textToParse.substring(currentIndex, currentIndex + 1)).setStyle(currentStyle));
                            currentIndex += 1;
                        }
                    } else {
                        ChatFormatting format = ChatFormatting.getByCode(formatChar);
                        if (format != null) {
                            currentStyle = currentStyle.applyFormat(format);
                            if (format == ChatFormatting.RESET) currentStyle = Style.EMPTY;
                        } else {
                            parentComponent.append(new TextComponent("§").setStyle(currentStyle)); // Append the unknown char as literal
                        }
                        currentIndex += 2;
                    }
                } else {
                    parentComponent.append(new TextComponent("§").setStyle(currentStyle));
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
                    parentComponent.append(new TextComponent("[").setStyle(currentStyle));
                    currentIndex += 1;
                }
            } else if (nextUrlFound && nextUrlStart == currentIndex) {
                String url = urlMatcher.group(0);
                Style urlStyle = currentStyle.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)));
                parentComponent.append(new TextComponent(url).setStyle(urlStyle));
                currentIndex = urlMatcher.end();
            } else {
                if (currentIndex < length) {
                    parentComponent.append(new TextComponent(textToParse.substring(currentIndex, currentIndex + 1)).setStyle(currentStyle));
                    currentIndex += 1;
                }
            }
        }
    }

    private MutableComponent parseTitleOrSubtitle(String rawText, Style baseStyle, ServerPlayer player) {
        MutableComponent parsedComponent = parseMessageInternal(rawText, player, Style.EMPTY);
        return applyBaseStyle(parsedComponent, baseStyle);
    }

    private MutableComponent applyBaseStyle(MutableComponent component, Style baseStyle) {
        MutableComponent styledComponent = component.copy();
        styledComponent.setStyle(baseStyle.applyTo(styledComponent.getStyle()));

        List<Component> children = new ArrayList<>(styledComponent.getSiblings());
        styledComponent.getSiblings().clear();
        for (Component child : children) {
            if (child instanceof MutableComponent) {
                styledComponent.append(applyBaseStyle((MutableComponent) child, baseStyle));
            } else {
                styledComponent.append(child.copy().setStyle(baseStyle.applyTo(child.getStyle())));
            }
        }
        return styledComponent;
    }

    private String formatUrl(String url) {
        if (url != null && !url.toLowerCase().startsWith("http://") && !url.toLowerCase().startsWith("https://")) {
            return "http://" + url;
        }
        return url;
    }

    private static class TagContext {
        private final MutableComponent component;
        private final Style currentStyle;
        private final ServerPlayer player;

        TagContext(MutableComponent component, Style style, ServerPlayer player) {
            this.component = component;
            this.currentStyle = style;
            this.player = player;
        }

        public MutableComponent getComponent() { return component; }
        public Style getCurrentStyle() { return currentStyle; }
        public ServerPlayer getPlayer() { return player; }
    }
}
