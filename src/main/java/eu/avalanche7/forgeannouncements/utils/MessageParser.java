package eu.avalanche7.forgeannouncements.utils;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerPlayer;

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
    private final Pattern urlPattern = Pattern.compile("https://?://\\S+");
    private final Map<Pattern, BiConsumer<Matcher, TagContext>> tagHandlers;
    private final Map<String, Component> messageCache = new ConcurrentHashMap<>(); // Cache Component
    private final Placeholders placeholders;

    public MessageParser(Placeholders placeholders) {
        this.placeholders = placeholders;
        this.tagHandlers = new LinkedHashMap<>();
        initializeTagHandlers();
    }

    private void initializeTagHandlers() {
        tagHandlers.put(Pattern.compile("\\[link=(.*?)\\](.*?)\\[/link\\]"), (matcher, context) -> {
            String url = matcher.group(1);
            String text = matcher.group(2);
            MutableComponent linkText = Component.literal(text); // Use Component.literal
            parseTextRecursive(text, linkText, context.getCurrentStyle(), context.getPlayer()); // Reparse inner text for colors etc.
            context.getComponent().append(linkText.setStyle(context.getCurrentStyle().withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)))));
        });
        tagHandlers.put(Pattern.compile("\\[command=(.*?)\\](.*?)\\[/command\\]"), (matcher, context) -> {
            String command = matcher.group(1);
            String text = matcher.group(2);
            String fullCommand = command.startsWith("/") ? command : "/" + command;
            MutableComponent cmdText = Component.literal(text);
            parseTextRecursive(text, cmdText, context.getCurrentStyle(), context.getPlayer());
            context.getComponent().append(cmdText.setStyle(context.getCurrentStyle().withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, fullCommand))));
        });
        tagHandlers.put(Pattern.compile("\\[hover=(.*?)\\](.*?)\\[/hover\\]", Pattern.DOTALL), (matcher, context) -> {
            String hoverTextContent = matcher.group(1);
            String mainTextContent = matcher.group(2);
            Component hoverComponent = parseMessageInternal(hoverTextContent, context.getPlayer(), Style.EMPTY); // Returns Component
            MutableComponent textWithHover = Component.literal(""); // Base for main text
            parseTextRecursive(mainTextContent, textWithHover, context.getCurrentStyle(), context.getPlayer());
            applyHoverToComponent(textWithHover, new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverComponent));
            context.getComponent().append(textWithHover);
        });
        tagHandlers.put(Pattern.compile("\\[divider\\]"), (matcher, context) -> {
            context.getComponent().append(Component.literal("--------------------") // Use Component.literal
                    .setStyle(context.getCurrentStyle().withColor(TextColor.fromLegacyFormat(ChatFormatting.GRAY))));
        });
        tagHandlers.put(Pattern.compile("\\[title=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (context.getPlayer() != null) {
                String titleText = matcher.group(1);
                Component titleComponent = parseTitleOrSubtitle(titleText, context.getCurrentStyle(), context.getPlayer());
                context.getPlayer().connection.send(new ClientboundClearTitlesPacket(true)); // true to clear subtitle too
                context.getPlayer().connection.send(new ClientboundSetTitleTextPacket(titleComponent));
            }
        });
        tagHandlers.put(Pattern.compile("\\[subtitle=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (context.getPlayer() != null) {
                String subtitleText = matcher.group(1);
                Component subtitleComponent = parseTitleOrSubtitle(subtitleText, context.getCurrentStyle(), context.getPlayer());
                context.getPlayer().connection.send(new ClientboundSetSubtitleTextPacket(subtitleComponent));
            }
        });
        tagHandlers.put(Pattern.compile("\\[center\\](.*?)\\[/center\\]", Pattern.DOTALL), (matcher, context) -> {
            String textToCenter = matcher.group(1);
            Component innerComponent = parseMessageInternal(textToCenter, context.getPlayer(), context.getCurrentStyle());
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
                context.getComponent().append(Component.literal(paddingSpaces).setStyle(context.getCurrentStyle()));
            }
            context.getComponent().append(innerComponent);
        });
    }

    private void applyHoverToComponent(MutableComponent component, HoverEvent hoverEvent) {
        component.setStyle(component.getStyle().withHoverEvent(hoverEvent));
        for (Component sibling : component.getSiblings()) {
            if (sibling instanceof MutableComponent mutableSibling) {
                applyHoverToComponent(mutableSibling, hoverEvent);
            }
        }
    }

    public Component parseMessage(String rawMessage, ServerPlayer player) {
        return parseMessageInternal(rawMessage, player, Style.EMPTY);
    }

    private Component parseMessageInternal(String rawMessage, ServerPlayer player, Style initialStyle) {
        if (rawMessage == null) {
            return Component.literal("").setStyle(initialStyle); // Use Component.literal
        }

        String processedMessage = this.placeholders.replacePlaceholders(rawMessage, player);

        Matcher hexMatcher = hexPattern.matcher(processedMessage);
        StringBuilder sb = new StringBuilder(); // Use StringBuilder
        while (hexMatcher.find()) {
            String hexColor = hexMatcher.group(1);
            hexMatcher.appendReplacement(sb, "§#" + hexColor);
        }
        hexMatcher.appendTail(sb);
        processedMessage = sb.toString();

        String messageForParsing = processedMessage.replace("&", "§");

        final String finalCacheKey = messageForParsing + "_style_" + initialStyle.hashCode() + (player != null ? player.getUUID().toString() : "null_player");
        if (messageCache.containsKey(finalCacheKey)) {
            return messageCache.get(finalCacheKey); // Return cached Component
        }

        MutableComponent rootComponent = Component.literal(""); // Use Component.literal
        parseTextRecursive(messageForParsing, rootComponent, initialStyle, player);

        messageCache.put(finalCacheKey, rootComponent);
        return rootComponent; // Return as Component
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
                parentComponent.append(Component.literal(textToParse.substring(currentIndex, firstEventIndex)).setStyle(currentStyle));
            }
            currentIndex = firstEventIndex;
            if (currentIndex == length) break;

            if (nextLegacyFormat == currentIndex) {
                if (currentIndex + 1 < length) {
                    char formatChar = textToParse.charAt(currentIndex + 1);
                    if (formatChar == '#') {
                        if (currentIndex + 7 < length) { // §#RRGGBB
                            String hex = textToParse.substring(currentIndex + 2, currentIndex + 8);
                            try {
                                currentStyle = currentStyle.withColor(TextColor.parseColor("#" + hex)); // Use TextColor.parseColor
                                currentIndex += 8;
                            } catch (Exception e) { // Catch broader exception from parseColor
                                parentComponent.append(Component.literal(textToParse.substring(currentIndex, currentIndex + 2)).setStyle(currentStyle));
                                currentIndex += 2;
                            }
                        } else {
                            parentComponent.append(Component.literal(textToParse.substring(currentIndex, currentIndex + 1)).setStyle(currentStyle));
                            currentIndex += 1;
                        }
                    } else {
                        ChatFormatting format = ChatFormatting.getByCode(formatChar);
                        if (format != null) {
                            currentStyle = currentStyle.applyFormat(format);
                            if (format.isColor() || format == ChatFormatting.RESET) { // Reset other formats if color or reset
                                currentStyle = format == ChatFormatting.RESET ? Style.EMPTY : Style.EMPTY.withColor(format);
                            }
                        } else {
                            parentComponent.append(Component.literal("§").setStyle(currentStyle));
                        }
                        currentIndex += 2;
                    }
                } else {
                    parentComponent.append(Component.literal("§").setStyle(currentStyle));
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
                    parentComponent.append(Component.literal("[").setStyle(currentStyle));
                    currentIndex += 1;
                }
            } else if (nextUrlFound && nextUrlStart == currentIndex) {
                String url = urlMatcher.group(0);
                Style urlStyle = currentStyle.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)));
                parentComponent.append(Component.literal(url).setStyle(urlStyle));
                currentIndex = urlMatcher.end();
            } else {
                if (currentIndex < length) {
                    parentComponent.append(Component.literal(textToParse.substring(currentIndex, currentIndex + 1)).setStyle(currentStyle));
                    currentIndex += 1;
                }
            }
        }
    }

    private Component parseTitleOrSubtitle(String rawText, Style baseStyle, ServerPlayer player) {
        Component parsedComponent = parseMessageInternal(rawText, player, Style.EMPTY);
        if (parsedComponent instanceof MutableComponent mutable) {
            return applyBaseStyle(mutable, baseStyle);
        }
        return parsedComponent.copy().withStyle(baseStyle); // Apply base style to a copy
    }

    private MutableComponent applyBaseStyle(MutableComponent component, Style baseStyle) {
        // Create a new component with the base style, then append children with the base style applied
        MutableComponent styledRoot = Component.literal("").withStyle(baseStyle);

        // Apply baseStyle to the component's own content if it has any (it shouldn't if it's a container)
        // More accurately, the style should be applied to its children.
        // The component itself might have an initial style that baseStyle should merge with.
        component.setStyle(baseStyle.applyTo(component.getStyle()));

        List<Component> children = new ArrayList<>(component.getSiblings());
        component.getSiblings().clear(); // Clear original siblings

        for (Component child : children) {
            if (child instanceof MutableComponent mutableChild) {
                component.append(applyBaseStyle(mutableChild, baseStyle));
            } else {
                component.append(child.copy().withStyle(baseStyle.applyTo(child.getStyle())));
            }
        }
        return component; // Return the modified original component
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
