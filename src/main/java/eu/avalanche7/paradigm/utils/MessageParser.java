package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageParser {

    private final Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private final Pattern urlPattern = Pattern.compile("https?://\\S+");
    private final Map<Pattern, BiConsumer<Matcher, TagContext>> tagHandlers;
    private final Map<String, IComponent> messageCache = new ConcurrentHashMap<>();
    private final Placeholders placeholders;
    private final IPlatformAdapter platformAdapter;

    public MessageParser(Placeholders placeholders, IPlatformAdapter platformAdapter) {
        this.placeholders = placeholders;
        this.platformAdapter = platformAdapter;
        this.tagHandlers = new LinkedHashMap<>();
        initializeTagHandlers();
    }

    private void initializeTagHandlers() {
        tagHandlers.put(Pattern.compile("\\[link=(.*?)\\]"), (matcher, context) -> {
            String url = matcher.group(1);
            context.getComponent().append(platformAdapter.createLiteralComponent(url)
                            .setStyle(context.getCurrentStyle().withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)))))
                    .append(platformAdapter.createLiteralComponent(" ").setStyle(context.getCurrentStyle()));
        });
        tagHandlers.put(Pattern.compile("\\[command=(.*?)\\]"), (matcher, context) -> {
            String command = matcher.group(1);
            String fullCommand = command.startsWith("/") ? command : "/" + command;
            context.getComponent().append(platformAdapter.createLiteralComponent(fullCommand)
                            .setStyle(context.getCurrentStyle().withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, fullCommand))))
                    .append(platformAdapter.createLiteralComponent(" ").setStyle(context.getCurrentStyle()));
        });
        tagHandlers.put(Pattern.compile("\\[hover=(.*?)\\](.*?)\\[/hover\\]", Pattern.DOTALL), (matcher, context) -> {
            String hoverTextContent = matcher.group(1);
            String mainTextContent = matcher.group(2);
            IComponent hoverComponent = parseMessageInternal(hoverTextContent, context.getPlayer(), Style.EMPTY);
            IComponent textWithHover = platformAdapter.createLiteralComponent("");
            parseTextRecursive(mainTextContent, textWithHover, context.getCurrentStyle(), context.getPlayer());
            applyHoverToComponent(textWithHover, new HoverEvent(HoverEvent.Action.SHOW_TEXT, ((MinecraftComponent) hoverComponent).getHandle()));
            context.getComponent().append(textWithHover);
        });
        tagHandlers.put(Pattern.compile("\\[divider\\]"), (matcher, context) -> {
            context.getComponent().append(platformAdapter.createLiteralComponent("--------------------")
                    .setStyle(context.getCurrentStyle().withColor(TextColor.fromLegacyFormat(ChatFormatting.GRAY))));
        });
        tagHandlers.put(Pattern.compile("\\[title=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (context.getPlayer() != null) {
                String titleText = matcher.group(1);
                IComponent titleComponent = parseTitleOrSubtitle(titleText, context.getCurrentStyle(), context.getPlayer());
                platformAdapter.clearTitles(context.getPlayer());
                platformAdapter.sendTitle(context.getPlayer(), titleComponent, platformAdapter.createLiteralComponent(""));
            }
        });
        tagHandlers.put(Pattern.compile("\\[subtitle=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (context.getPlayer() != null) {
                String subtitleText = matcher.group(1);
                IComponent subtitleComponent = parseTitleOrSubtitle(subtitleText, context.getCurrentStyle(), context.getPlayer());
                platformAdapter.sendSubtitle(context.getPlayer(), subtitleComponent);
            }
        });
        tagHandlers.put(Pattern.compile("\\[center\\](.*?)\\[/center\\]", Pattern.DOTALL), (matcher, context) -> {
            String textToCenter = matcher.group(1);
            IComponent innerComponent = parseMessageInternal(textToCenter, context.getPlayer(), context.getCurrentStyle());
            String plainInnerText = innerComponent.getRawText();
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
                context.getComponent().append(platformAdapter.createLiteralComponent(paddingSpaces).setStyle(context.getCurrentStyle()));
            }
            context.getComponent().append(innerComponent);
        });
    }

    private void applyHoverToComponent(IComponent component, HoverEvent hoverEvent) {
        component.setStyle(component.getStyle().withHoverEvent(hoverEvent));
        for (Object sibling : component.getSiblings()) {
            if (sibling instanceof IComponent mutableSibling) {
                applyHoverToComponent(mutableSibling, hoverEvent);
            }
        }
    }

    public IComponent parseMessage(String rawMessage, IPlayer player) {
        return parseMessageInternal(rawMessage, player, Style.EMPTY);
    }

    private IComponent parseMessageInternal(String rawMessage, IPlayer player, Style initialStyle) {
        if (rawMessage == null) {
            return platformAdapter.createLiteralComponent("").setStyle(initialStyle);
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

        final String finalCacheKey = messageForParsing + "_style_" + initialStyle.hashCode() + (player != null ? player.getUUID() : "null_player");
        if (messageCache.containsKey(finalCacheKey)) {
            return messageCache.get(finalCacheKey).copy();
        }

        IComponent rootComponent = platformAdapter.createLiteralComponent("");
        parseTextRecursive(messageForParsing, rootComponent, initialStyle, player);

        messageCache.put(finalCacheKey, rootComponent);
        return rootComponent;
    }

    private void parseTextRecursive(String textToParse, IComponent parentComponent, Style currentStyle, IPlayer player) {
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
                parentComponent.append(platformAdapter.createLiteralComponent(textToParse.substring(currentIndex, firstEventIndex)).setStyle(currentStyle));
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
                                parentComponent.append(platformAdapter.createLiteralComponent(textToParse.substring(currentIndex, currentIndex + 2)).setStyle(currentStyle));
                                currentIndex += 2;
                            }
                        } else {
                            parentComponent.append(platformAdapter.createLiteralComponent(textToParse.substring(currentIndex, currentIndex + 1)).setStyle(currentStyle));
                            currentIndex += 1;
                        }
                    } else {
                        ChatFormatting format = ChatFormatting.getByCode(formatChar);
                        if (format != null) {
                            currentStyle = currentStyle.applyFormat(format);
                            if (format == ChatFormatting.RESET) currentStyle = Style.EMPTY;
                        } else {
                            parentComponent.append(platformAdapter.createLiteralComponent("§").setStyle(currentStyle));
                        }
                        currentIndex += 2;
                    }
                } else {
                    parentComponent.append(platformAdapter.createLiteralComponent("§").setStyle(currentStyle));
                    currentIndex += 1;
                }
            }
            else if (nextTagStart == currentIndex) {
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
                    parentComponent.append(platformAdapter.createLiteralComponent("[").setStyle(currentStyle));
                    currentIndex += 1;
                }
            }
            else if (nextUrlFound && nextUrlStart == currentIndex) {
                String url = urlMatcher.group(0);
                Style urlStyle = currentStyle.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)));
        parentComponent.append(platformAdapter.createLiteralComponent(url).setStyle(urlStyle));
                currentIndex = urlMatcher.end();
            } else {
                if (currentIndex < length) {
                    parentComponent.append(platformAdapter.createLiteralComponent(textToParse.substring(currentIndex, currentIndex + 1)).setStyle(currentStyle));
                    currentIndex += 1;
                }
            }
        }
    }

    private IComponent parseTitleOrSubtitle(String rawText, Style baseStyle, IPlayer player) {
        IComponent parsedComponent = parseMessageInternal(rawText, player, Style.EMPTY);
        return applyBaseStyle(parsedComponent, baseStyle);
    }

    private IComponent applyBaseStyle(IComponent component, Style baseStyle) {
        if (component instanceof MinecraftComponent mc) {
            MutableComponent root = mc.getHandle().copy();
            root.setStyle(baseStyle.applyTo(root.getStyle()));
            java.util.List<Component> children = new java.util.ArrayList<>(root.getSiblings());
            try {
                root.getSiblings().clear();
            } catch (UnsupportedOperationException ignored) {
                root = new TextComponent(root.getString()).setStyle(root.getStyle());
            }
            for (Component child : children) {
                root.append(applyBaseStyleRecursive(child, baseStyle));
            }
            return new MinecraftComponent(root);
        }
        IComponent styledComponent = component.copy();
        styledComponent.setStyle(baseStyle.applyTo(styledComponent.getStyle()));
        return styledComponent;
    }

    private Component applyBaseStyleRecursive(Component comp, Style baseStyle) {
        MutableComponent copy = comp.copy();
        copy.setStyle(baseStyle.applyTo(copy.getStyle()));
        java.util.List<Component> kids = new java.util.ArrayList<>(copy.getSiblings());
        try {
            copy.getSiblings().clear();
        } catch (UnsupportedOperationException ignored) {
            copy = new TextComponent(copy.getString()).setStyle(copy.getStyle());
            kids = java.util.List.of();
        }
        for (Component k : kids) {
            copy.append(applyBaseStyleRecursive(k, baseStyle));
        }
        return copy;
    }

    private String formatUrl(String url) {
        if (url != null && !url.toLowerCase().startsWith("http://") && !url.toLowerCase().startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }

    private static class TagContext {
        private final IComponent component;
        private final Style currentStyle;
        private final IPlayer player;

        TagContext(IComponent component, Style style, IPlayer player) {
            this.component = component;
            this.currentStyle = style;
            this.player = player;
        }

        public IComponent getComponent() { return component; }
        public Style getCurrentStyle() { return currentStyle; }
        public IPlayer getPlayer() { return player; }
    }
}
