package eu.avalanche7.paradigm.utils;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageParser {

    private final Pattern urlPattern = Pattern.compile("https?://[\\w\\d./?=#&%-]+");
    private final Map<Pattern, BiConsumer<Matcher, TagContext>> tagHandlers;
    private final Map<String, ITextComponent> messageCache = new ConcurrentHashMap<>();
    private final Placeholders placeholders;

    public MessageParser(Placeholders placeholders) {
        this.placeholders = placeholders;
        this.tagHandlers = new LinkedHashMap<>();
        initializeTagHandlers();
    }

    private void initializeTagHandlers() {
        tagHandlers.put(Pattern.compile("\\[link=(.*?)\\]"), (matcher, context) -> {
            String url = matcher.group(1);
            ITextComponent linkContent = parseMessageInternal(url, context.getPlayer(), context.getCurrentStyle().createDeepCopy());
            linkContent.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)));
            context.getComponent().appendSibling(linkContent);
        });

        tagHandlers.put(Pattern.compile("\\[command=(.*?)\\]"), (matcher, context) -> {
            String command = matcher.group(1);
            String fullCommand = command.startsWith("/") ? command : "/" + command;
            ITextComponent commandContent = parseMessageInternal(fullCommand, context.getPlayer(), context.getCurrentStyle().createDeepCopy());
            commandContent.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, fullCommand));
            context.getComponent().appendSibling(commandContent);
        });

        tagHandlers.put(Pattern.compile("\\[hover=(.*?)\\](.*?)\\[/hover\\]", Pattern.DOTALL), (matcher, context) -> {
            String hoverTextContent = matcher.group(1);
            String mainTextContent = matcher.group(2);
            ITextComponent hoverComponent = parseMessageInternal(hoverTextContent, context.getPlayer(), new Style());
            HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverComponent);
            ITextComponent textWithHover = new TextComponentString("");
            parseTextRecursive(mainTextContent, textWithHover, context.getCurrentStyle(), context.getPlayer());
            applyHoverToComponent(textWithHover, hoverEvent);
            context.getComponent().appendSibling(textWithHover);
        });

        tagHandlers.put(Pattern.compile("\\[divider\\]"), (matcher, context) -> {
            context.getComponent().appendSibling(new TextComponentString("--------------------")
                    .setStyle(new Style().setColor(TextFormatting.GRAY).setStrikethrough(true)));
        });

        tagHandlers.put(Pattern.compile("\\[title=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (context.getPlayer() != null) {
                String titleText = matcher.group(1);
                ITextComponent titleComponent = parseTitleOrSubtitle(titleText, context.getCurrentStyle(), context.getPlayer());
                context.getPlayer().connection.sendPacket(new SPacketTitle(SPacketTitle.Type.RESET, null));
                context.getPlayer().connection.sendPacket(new SPacketTitle(SPacketTitle.Type.TITLE, titleComponent));
            }
        });

        tagHandlers.put(Pattern.compile("\\[subtitle=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (context.getPlayer() != null) {
                String subtitleText = matcher.group(1);
                ITextComponent subtitleComponent = parseTitleOrSubtitle(subtitleText, context.getCurrentStyle(), context.getPlayer());
                context.getPlayer().connection.sendPacket(new SPacketTitle(SPacketTitle.Type.SUBTITLE, subtitleComponent));
            }
        });

        tagHandlers.put(Pattern.compile("\\[center\\](.*?)\\[/center\\]", Pattern.DOTALL), (matcher, context) -> {
            String textToCenter = matcher.group(1);
            ITextComponent innerComponent = parseMessageInternal(textToCenter, context.getPlayer(), context.getCurrentStyle());
            final int APPROXIMATE_CHAT_WIDTH_CHARS = 53;
            String plainInnerText = innerComponent.getUnformattedText();
            int textLength = plainInnerText.length();

            if (textLength < APPROXIMATE_CHAT_WIDTH_CHARS) {
                int totalPadding = APPROXIMATE_CHAT_WIDTH_CHARS - textLength;
                int leftPadding = totalPadding / 2;
                if (leftPadding > 0) {
                    String paddingSpaces = new String(new char[leftPadding]).replace('\0', ' ');
                    context.getComponent().appendSibling(new TextComponentString(paddingSpaces));
                }
            }
            context.getComponent().appendSibling(innerComponent);
        });
    }

    private void applyHoverToComponent(ITextComponent component, HoverEvent hoverEvent) {
        component.getStyle().setHoverEvent(hoverEvent);
        for (ITextComponent sibling : component.getSiblings()) {
            applyHoverToComponent(sibling, hoverEvent);
        }
    }

    public ITextComponent parseMessage(String rawMessage, EntityPlayerMP player) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return new TextComponentString("");
        }
        return parseMessageInternal(rawMessage, player, new Style());
    }

    private ITextComponent parseMessageInternal(String rawMessage, EntityPlayerMP player, Style initialStyle) {
        String processedMessage = this.placeholders.replacePlaceholders(rawMessage, player);
        String messageForParsing = processedMessage.replace("&", "§");
        final String cacheKey = messageForParsing;
        ITextComponent cached = messageCache.get(cacheKey);
        if (cached != null) {
            return cached.createCopy();
        }

        ITextComponent rootComponent = new TextComponentString("");
        parseTextRecursive(messageForParsing, rootComponent, initialStyle, player);

        messageCache.put(cacheKey, rootComponent);
        return rootComponent;
    }

    private void parseTextRecursive(String textToParse, ITextComponent parentComponent, Style currentStyle, EntityPlayerMP player) {
        int currentIndex = 0;
        int length = textToParse.length();
        Matcher urlMatcher = urlPattern.matcher(textToParse);

        while (currentIndex < length) {
            int nextLegacyFormat = textToParse.indexOf('§', currentIndex);
            int nextTagStart = textToParse.indexOf('[', currentIndex);

            int nextUrlStart = -1;
            if (urlMatcher.find(currentIndex)) {
                nextUrlStart = urlMatcher.start();
            }

            int firstEventIndex = length;
            if (nextLegacyFormat != -1) firstEventIndex = Math.min(firstEventIndex, nextLegacyFormat);
            if (nextTagStart != -1) firstEventIndex = Math.min(firstEventIndex, nextTagStart);
            if (nextUrlStart != -1) firstEventIndex = Math.min(firstEventIndex, nextUrlStart);

            if (firstEventIndex > currentIndex) {
                parentComponent.appendSibling(new TextComponentString(textToParse.substring(currentIndex, firstEventIndex)).setStyle(currentStyle.createDeepCopy()));
            }

            if (firstEventIndex == length) break;

            if (nextLegacyFormat == firstEventIndex) {
                if (firstEventIndex + 1 < length) {
                    currentStyle = applyFormatCode(currentStyle, textToParse.charAt(firstEventIndex + 1));
                    currentIndex = firstEventIndex + 2;
                } else {
                    parentComponent.appendSibling(new TextComponentString("§").setStyle(currentStyle.createDeepCopy()));
                    currentIndex = firstEventIndex + 1;
                }
            } else if (nextTagStart == firstEventIndex) {
                boolean tagHandled = false;
                for (Map.Entry<Pattern, BiConsumer<Matcher, TagContext>> entry : tagHandlers.entrySet()) {
                    Pattern tagPattern = entry.getKey();
                    Matcher tagMatcher = tagPattern.matcher(textToParse);
                    if (tagMatcher.find(firstEventIndex) && tagMatcher.start() == firstEventIndex) {
                        TagContext context = new TagContext(parentComponent, currentStyle, player);
                        entry.getValue().accept(tagMatcher, context);
                        currentIndex = tagMatcher.end();
                        tagHandled = true;
                        break;
                    }
                }
                if (!tagHandled) {
                    parentComponent.appendSibling(new TextComponentString("[").setStyle(currentStyle.createDeepCopy()));
                    currentIndex = firstEventIndex + 1;
                }
            } else if (nextUrlStart != -1 && nextUrlStart == firstEventIndex) {
                String url = urlMatcher.group(0);
                Style urlStyle = currentStyle.createDeepCopy().setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)));
                parentComponent.appendSibling(new TextComponentString(url).setStyle(urlStyle));
                currentIndex = urlMatcher.end();
            } else {
                currentIndex = firstEventIndex + 1;
            }
        }
    }
    private Style applyFormatCode(Style style, char colorCode) {
        switch (Character.toLowerCase(colorCode)) {
            case '0': return style.createDeepCopy().setColor(TextFormatting.BLACK);
            case '1': return style.createDeepCopy().setColor(TextFormatting.DARK_BLUE);
            case '2': return style.createDeepCopy().setColor(TextFormatting.DARK_GREEN);
            case '3': return style.createDeepCopy().setColor(TextFormatting.DARK_AQUA);
            case '4': return style.createDeepCopy().setColor(TextFormatting.DARK_RED);
            case '5': return style.createDeepCopy().setColor(TextFormatting.DARK_PURPLE);
            case '6': return style.createDeepCopy().setColor(TextFormatting.GOLD);
            case '7': return style.createDeepCopy().setColor(TextFormatting.GRAY);
            case '8': return style.createDeepCopy().setColor(TextFormatting.DARK_GRAY);
            case '9': return style.createDeepCopy().setColor(TextFormatting.BLUE);
            case 'a': return style.createDeepCopy().setColor(TextFormatting.GREEN);
            case 'b': return style.createDeepCopy().setColor(TextFormatting.AQUA);
            case 'c': return style.createDeepCopy().setColor(TextFormatting.RED);
            case 'd': return style.createDeepCopy().setColor(TextFormatting.LIGHT_PURPLE);
            case 'e': return style.createDeepCopy().setColor(TextFormatting.YELLOW);
            case 'f': return style.createDeepCopy().setColor(TextFormatting.WHITE);
            case 'k': return style.createDeepCopy().setObfuscated(true);
            case 'l': return style.createDeepCopy().setBold(true);
            case 'm': return style.createDeepCopy().setStrikethrough(true);
            case 'n': return style.createDeepCopy().setUnderlined(true);
            case 'o': return style.createDeepCopy().setItalic(true);
            case 'r': return new Style();
            default: return style;
        }
    }

    private ITextComponent parseTitleOrSubtitle(String rawText, Style baseStyle, EntityPlayerMP player) {
        ITextComponent parsedComponent = parseMessageInternal(rawText, player, new Style());
        return applyBaseStyle(parsedComponent, baseStyle);
    }
    private ITextComponent applyBaseStyle(ITextComponent component, Style baseStyle) {
        component.getStyle().setParentStyle(baseStyle);
        for (ITextComponent child : component.getSiblings()) {
            applyBaseStyle(child, baseStyle);
        }
        return component;
    }

    private String formatUrl(String url) {
        if (url != null && !url.toLowerCase().startsWith("http://") && !url.toLowerCase().startsWith("https://")) {
            return "http://" + url;
        }
        return url;
    }

    private static class TagContext {
        private final ITextComponent component;
        private final Style currentStyle;
        private final EntityPlayerMP player;

        TagContext(ITextComponent component, Style style, EntityPlayerMP player) {
            this.component = component;
            this.currentStyle = style;
            this.player = player;
        }

        public ITextComponent getComponent() { return component; }
        public Style getCurrentStyle() { return currentStyle; }
        public EntityPlayerMP getPlayer() { return player; }
    }
}