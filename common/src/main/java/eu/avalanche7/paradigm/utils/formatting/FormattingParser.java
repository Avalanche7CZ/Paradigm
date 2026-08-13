package eu.avalanche7.paradigm.utils.formatting;

import java.util.List;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.formatting.tags.Tag;
import eu.avalanche7.paradigm.utils.formatting.tags.TagRegistry;

public class FormattingParser {
    private final TagRegistry tagRegistry;
    private final IPlatformAdapter platformAdapter;
    private final Placeholders placeholders;
    private final Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private final Pattern urlPattern = Pattern.compile("https?://\\S+");

    private static class TagState {
        Tag tag;
        String arguments;

        TagState(Tag tag, String arguments) {
            this.tag = tag;
            this.arguments = arguments;
        }
    }

    public FormattingParser(IPlatformAdapter platformAdapter, Placeholders placeholders) {
        this.platformAdapter = platformAdapter;
        this.placeholders = placeholders;
        this.tagRegistry = new TagRegistry(platformAdapter);
    }

    public TagRegistry getTagRegistry() {
        return tagRegistry;
    }

    public IComponent parse(String rawMessage, IPlayer player) {
        return parse(rawMessage, player, null, ComponentSlots.none());
    }

    public IComponent parse(String rawMessage, IPlayer player, Object baseStyle, ComponentSlots slots) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            IComponent empty = platformAdapter.createComponentFromLiteral("");
            if (baseStyle != null) {
                empty.setStyle(baseStyle);
            }
            return empty;
        }

        String processedMessage = placeholders != null
                ? placeholders.replacePlaceholders(rawMessage, player)
                : rawMessage;

        Matcher hexMatcher = hexPattern.matcher(processedMessage);
        StringBuilder sb = new StringBuilder();
        while (hexMatcher.find()) {
            String hexColor = hexMatcher.group(1);
            hexMatcher.appendReplacement(sb, "&#" + hexColor);
        }
        hexMatcher.appendTail(sb);
        processedMessage = sb.toString();

        Tokenizer tokenizer = new Tokenizer(processedMessage);
        List<Token> tokens = tokenizer.tokenize();

        IComponent rootComponent = platformAdapter.createComponentFromLiteral("");
        if (baseStyle != null) {
            rootComponent.setStyle(baseStyle);
        }
        FormattingContext context = new FormattingContext(rootComponent, player, baseStyle);
        context.setParser(this);
        context.setSlots(slots);
        Stack<TagState> tagStack = new Stack<>();

        for (Token token : tokens) {
            switch (token.getType()) {
                case TEXT -> {
                    IComponent targetComponent = context.getCurrentComponent();
                    appendText(targetComponent, token.getValue(), context);
                }
                case ESCAPE -> appendText(context.getCurrentComponent(), token.getValue(), context);
                case TAG_OPEN -> {
                    String tagContent = token.getValue();
                    int colonIndex = findFirstColonOutsideQuotes(tagContent);
                    String tagName = colonIndex >= 0 ? tagContent.substring(0, colonIndex) : tagContent;
                    String arguments = colonIndex >= 0 ? tagContent.substring(colonIndex + 1) : "";

                    Tag tag = tagRegistry.getTag(tagName);
                    if (tag != null && tag.canOpen()) {
                        tag.process(context, arguments);
                        tagStack.push(new TagState(tag, arguments));
                    } else {
                        appendText(context.getCurrentComponent(), "<" + tagContent + ">", context);
                    }
                }
                case TAG_CLOSE -> {
                    if (!tagStack.isEmpty()) {
                        TagState state = tagStack.pop();
                        state.tag.close(context);
                    }
                }
                case TAG_SELF_CLOSE -> {
                    String selfCloseTagName = token.getValue();
                    int selfCloseColonIdx = findFirstColonOutsideQuotes(selfCloseTagName);
                    String selfCloseTagBase = selfCloseColonIdx >= 0 ? selfCloseTagName.substring(0, selfCloseColonIdx) : selfCloseTagName;
                    String selfCloseArgs = selfCloseColonIdx >= 0 ? selfCloseTagName.substring(selfCloseColonIdx + 1) : "";

                    Tag selfCloseTag = tagRegistry.getTag(selfCloseTagBase);
                    if (selfCloseTag != null && selfCloseTag.isSelfClosing()) {
                        selfCloseTag.process(context, selfCloseArgs);
                    }
                }
                case EOF -> {
                }
            }
        }

        return rootComponent;
    }

    private void appendText(IComponent parent, String text, FormattingContext context) {
        if (text == null || text.isEmpty()) {
            return;
        }

        ComponentSlots slots = context.getSlots();
        if (slots.isEmpty() || text.indexOf(ComponentSlots.MARKER_START) < 0) {
            appendPlainText(parent, text, context);
            return;
        }

        int cursor = 0;
        while (cursor < text.length()) {
            int markerStart = text.indexOf(ComponentSlots.MARKER_START, cursor);
            if (markerStart < 0) {
                break;
            }
            int markerEnd = text.indexOf(ComponentSlots.MARKER_END, markerStart + 1);
            if (markerEnd < 0) {
                break;
            }

            appendPlainText(parent, text.substring(cursor, markerStart), context);
            appendSlot(parent, text.substring(markerStart + 1, markerEnd), context);
            cursor = markerEnd + 1;
        }
        appendPlainText(parent, text.substring(cursor), context);
    }

    private void appendSlot(IComponent parent, String rawIndex, FormattingContext context) {
        int index;
        try {
            index = Integer.parseInt(rawIndex);
        } catch (NumberFormatException malformed) {
            return;
        }
        ComponentSlot slot = context.getSlots().at(index);
        if (slot == null) {
            return;
        }
        IComponent rendered = slot.render(context.getCurrentStyle());
        if (rendered != null) {
            parent.append(rendered);
        }
    }

    private void appendPlainText(IComponent parent, String text, FormattingContext context) {
        if (text == null || text.isEmpty()) {
            return;
        }

        int currentIndex = 0;
        Matcher urlMatcher = urlPattern.matcher(text);

        while (currentIndex < text.length()) {
            boolean urlFound = false;
            int nextUrlStart = text.length();

            while (urlMatcher.find(currentIndex)) {
                if (urlMatcher.start() >= currentIndex) {
                    nextUrlStart = urlMatcher.start();
                    urlFound = true;
                    break;
                }
            }

            if (nextUrlStart > currentIndex) {
                String plainText = text.substring(currentIndex, nextUrlStart);
                IComponent textComponent = platformAdapter.createComponentFromLiteral(plainText);
                Object style = context.getCurrentStyle();
                if (style != null) {
                    textComponent.setStyle(style);
                }
                parent.append(textComponent);
            }

            if (urlFound) {
                String url = urlMatcher.group(0);
                String fullUrl = url.startsWith("http://") || url.startsWith("https://") ? url : "https://" + url;

                IComponent urlComponent = platformAdapter.createComponentFromLiteral(url);
                Object urlStyle = platformAdapter.createStyleWithClickEvent(context.getCurrentStyle(), "open_url", fullUrl);
                urlComponent.setStyle(urlStyle);
                parent.append(urlComponent);
                currentIndex = urlMatcher.end();
            } else {
                break;
            }
        }
    }

    public void registerCustomTag(Tag tag) {
        tagRegistry.registerTag(tag);
    }

    private int findFirstColonOutsideQuotes(String text) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inAngleBracket = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '<' && !inSingleQuote && !inDoubleQuote) {
                inAngleBracket = true;
            } else if (c == '>' && !inSingleQuote && !inDoubleQuote) {
                inAngleBracket = false;
            } else if (c == '\'' && !inDoubleQuote && !inAngleBracket) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote && !inAngleBracket) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == ':' && !inSingleQuote && !inDoubleQuote && !inAngleBracket) {
                return i;
            }
        }

        return -1;
    }
}
