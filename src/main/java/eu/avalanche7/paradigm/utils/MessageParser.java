package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;

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
            if (platformAdapter == null) return;
            String url = matcher.group(1);
            IComponent urlComponent = new MinecraftComponent(platformAdapter.createLiteralComponent(url));
            urlComponent.setStyle(context.getCurrentStyle().withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url))));
            context.getComponent().append(urlComponent);
            IComponent spaceComponent = new MinecraftComponent(platformAdapter.createLiteralComponent(" "));
            spaceComponent.setStyle(context.getCurrentStyle());
            context.getComponent().append(spaceComponent);
        });
        tagHandlers.put(Pattern.compile("\\[command=(.*?)\\]"), (matcher, context) -> {
            if (platformAdapter == null) return;
            String command = matcher.group(1);
            String fullCommand = command.startsWith("/") ? command : "/" + command;
            IComponent cmdComponent = new MinecraftComponent(platformAdapter.createLiteralComponent(fullCommand));
            cmdComponent.setStyle(context.getCurrentStyle().withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, fullCommand)));
            context.getComponent().append(cmdComponent);
            IComponent spaceComponent = new MinecraftComponent(platformAdapter.createLiteralComponent(" "));
            spaceComponent.setStyle(context.getCurrentStyle());
            context.getComponent().append(spaceComponent);
        });
        tagHandlers.put(Pattern.compile("\\[hover=(.*?)\\](.*?)\\[/hover\\]", Pattern.DOTALL), (matcher, context) -> {
            if (platformAdapter == null) return;
            String hoverTextContent = matcher.group(1);
            String mainTextContent = matcher.group(2);
            IComponent hoverComponent = parseMessageInternal(hoverTextContent, context.getPlayer(), Style.EMPTY);
            IComponent textWithHover = new MinecraftComponent(platformAdapter.createLiteralComponent(""));
            parseTextRecursive(mainTextContent, textWithHover, context.getCurrentStyle(), context.getPlayer());
            textWithHover = textWithHover.onHoverComponent(hoverComponent);
            context.getComponent().append(textWithHover);
        });
        tagHandlers.put(Pattern.compile("\\[divider\\]"), (matcher, context) -> {
            if (platformAdapter == null) return;
            IComponent dividerComponent = new MinecraftComponent(platformAdapter.createLiteralComponent("--------------------"));
            dividerComponent.setStyle(context.getCurrentStyle().withColor(TextColor.fromFormatting(Formatting.GRAY)));
            context.getComponent().append(dividerComponent);
        });
        tagHandlers.put(Pattern.compile("\\[title=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (platformAdapter == null || context.getPlayer() == null) return;
            String titleText = matcher.group(1);
            IComponent titleComponent = parseTitleOrSubtitle(titleText, context.getCurrentStyle(), context.getPlayer());
            platformAdapter.clearTitles(context.getPlayer().getOriginalPlayer());
            platformAdapter.sendTitle(
                context.getPlayer().getOriginalPlayer(),
                titleComponent instanceof MinecraftComponent mc ? mc.getHandle() : new MinecraftComponent(platformAdapter.createLiteralComponent(titleComponent.getRawText())).getHandle(),
                new MinecraftComponent(platformAdapter.createLiteralComponent("")).getHandle()
            );
        });
        tagHandlers.put(Pattern.compile("\\[subtitle=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (platformAdapter == null || context.getPlayer() == null) return;
            String subtitleText = matcher.group(1);
            IComponent subtitleComponent = parseTitleOrSubtitle(subtitleText, context.getCurrentStyle(), context.getPlayer());
            platformAdapter.sendSubtitle(
                context.getPlayer().getOriginalPlayer(),
                subtitleComponent instanceof MinecraftComponent mc ? mc.getHandle() : new MinecraftComponent(platformAdapter.createLiteralComponent(subtitleComponent.getRawText())).getHandle()
            );
        });
        tagHandlers.put(Pattern.compile("\\[center\\](.*?)\\[/center\\]", Pattern.DOTALL), (matcher, context) -> {
            if (platformAdapter == null) return;
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
                IComponent padComponent = new MinecraftComponent(platformAdapter.createLiteralComponent(paddingSpaces));
                padComponent.setStyle(context.getCurrentStyle());
                context.getComponent().append(padComponent);
            }
            context.getComponent().append(innerComponent);
        });
    }

    private void applyHoverToComponent(MinecraftComponent component, HoverEvent hoverEvent) {
        component.getHandle().setStyle(component.getHandle().getStyle().withHoverEvent(hoverEvent));
        for (net.minecraft.text.Text sibling : component.getHandle().getSiblings()) {
            if (sibling instanceof MutableText mutableSibling) {
                mutableSibling.setStyle(mutableSibling.getStyle().withHoverEvent(hoverEvent));
            }
        }
    }

    public IComponent parseMessage(String rawMessage, IPlayer player) {
        return parseMessageInternal(rawMessage, player, Style.EMPTY);
    }

    private IComponent parseMessageInternal(String rawMessage, IPlayer player, Style initialStyle) {
        if (rawMessage == null) {
            IComponent emptyComponent = new MinecraftComponent(platformAdapter != null ?
                platformAdapter.createLiteralComponent("") : Text.literal(""));
            emptyComponent.setStyle(initialStyle);
            return emptyComponent;
        }

        if (platformAdapter == null) {
            // Fallback when platformAdapter is null - return simple text component
            return new MinecraftComponent(Text.literal(rawMessage));
        }

        String processedMessage = this.placeholders.replacePlaceholders(rawMessage, player != null ? player.getOriginalPlayer() : null);
        Matcher hexMatcher = hexPattern.matcher(processedMessage);
        StringBuilder sb = new StringBuilder();
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
        IComponent rootComponent = new MinecraftComponent(platformAdapter.createLiteralComponent(""));
        rootComponent.setStyle(initialStyle);
        parseTextRecursive(messageForParsing, rootComponent, initialStyle, player);
        messageCache.put(finalCacheKey, rootComponent);
        return rootComponent;
    }

    private void parseTextRecursive(String textToParse, IComponent parentComponent, Style currentStyle, IPlayer player) {
        if (platformAdapter == null) {
            parentComponent.append(new MinecraftComponent(Text.literal(textToParse)).setStyle(currentStyle));
            return;
        }
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
                parentComponent.append(new MinecraftComponent(platformAdapter.createLiteralComponent(textToParse.substring(currentIndex, firstEventIndex))).setStyle(currentStyle));
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
                                parentComponent.append(new MinecraftComponent(platformAdapter.createLiteralComponent(textToParse.substring(currentIndex, currentIndex + 2))).setStyle(currentStyle));
                                currentIndex += 2;
                            }
                        } else {
                            parentComponent.append(new MinecraftComponent(platformAdapter.createLiteralComponent(textToParse.substring(currentIndex, currentIndex + 1))).setStyle(currentStyle));
                            currentIndex += 1;
                        }
                    } else {
                        Formatting format = Formatting.byCode(formatChar);
                        if (format != null) {
                            currentStyle = currentStyle.withFormatting(format);
                            if (format == Formatting.RESET) currentStyle = Style.EMPTY;
                        } else {
                            parentComponent.append(new MinecraftComponent(platformAdapter.createLiteralComponent("§")).setStyle(currentStyle));
                        }
                        currentIndex += 2;
                    }
                } else {
                    parentComponent.append(new MinecraftComponent(platformAdapter.createLiteralComponent("§")).setStyle(currentStyle));
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
                    parentComponent.append(new MinecraftComponent(platformAdapter.createLiteralComponent("[")).setStyle(currentStyle));
                    currentIndex += 1;
                }
            } else if (nextUrlFound && nextUrlStart == currentIndex) {
                String url = urlMatcher.group(0);
                Style urlStyle = currentStyle.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)));
                parentComponent.append(new MinecraftComponent(platformAdapter.createLiteralComponent(url)).setStyle(urlStyle));
                currentIndex = urlMatcher.end();
            } else {
                if (currentIndex < length) {
                    parentComponent.append(new MinecraftComponent(platformAdapter.createLiteralComponent(textToParse.substring(currentIndex, currentIndex + 1))).setStyle(currentStyle));
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
            MutableText root = mc.getHandle().copy();
            root.setStyle(baseStyle.withParent(root.getStyle()));
            java.util.List<Text> children = new java.util.ArrayList<>(root.getSiblings());
            try {
                root.getSiblings().clear();
            } catch (UnsupportedOperationException ignored) {
                root = Text.literal(root.getString()).setStyle(root.getStyle());
            }
            for (Text child : children) {
                if (child instanceof MutableText mutableChild) {
                    root.append(applyBaseStyleToMutable(mutableChild, baseStyle));
                } else {
                    root.append(Text.literal(child.getString()).setStyle(baseStyle.withParent(child.getStyle())));
                }
            }
            return new MinecraftComponent(root);
        }
        IComponent styledComponent = component.copy();
        styledComponent.setStyle(baseStyle.withParent(styledComponent.getStyle()));
        return styledComponent;
    }

    private MutableText applyBaseStyleToMutable(MutableText comp, Style baseStyle) {
        MutableText copy = comp.copy();
        copy.setStyle(baseStyle.withParent(copy.getStyle()));
        java.util.List<Text> kids = new java.util.ArrayList<>(copy.getSiblings());
        try {
            copy.getSiblings().clear();
        } catch (UnsupportedOperationException ignored) {
            copy = Text.literal(copy.getString()).setStyle(copy.getStyle());
            kids = java.util.List.of();
        }
        for (Text k : kids) {
            if (k instanceof MutableText mutableK) {
                copy.append(applyBaseStyleToMutable(mutableK, baseStyle));
            } else {
                copy.append(Text.literal(k.getString()).setStyle(baseStyle.withParent(k.getStyle())));
            }
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
