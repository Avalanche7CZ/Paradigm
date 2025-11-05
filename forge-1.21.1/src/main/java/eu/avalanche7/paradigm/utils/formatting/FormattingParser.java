package eu.avalanche7.paradigm.utils.formatting;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.formatting.tags.Tag;
import eu.avalanche7.paradigm.utils.formatting.tags.TagRegistry;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        if (rawMessage == null || rawMessage.isEmpty()) {
            return platformAdapter.createLiteralComponent("");
        }

        String processedMessage = this.placeholders.replacePlaceholders(rawMessage, player);

        Matcher hexMatcher = hexPattern.matcher(processedMessage);
        StringBuffer sb = new StringBuffer();
        while (hexMatcher.find()) {
            String hexColor = hexMatcher.group(1);
            hexMatcher.appendReplacement(sb, "&#" + hexColor);
        }
        hexMatcher.appendTail(sb);
        processedMessage = sb.toString();

        Tokenizer tokenizer = new Tokenizer(processedMessage);
        List<Token> tokens = tokenizer.tokenize();

        IComponent rootComponent = platformAdapter.createLiteralComponent("");
        FormattingContext context = new FormattingContext(rootComponent, player, Style.EMPTY);
        context.setParser(this);
        Stack<TagState> tagStack = new Stack<>();

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);

            switch (token.getType()) {
                case TEXT:
                    IComponent targetComponent = context.getCurrentComponent();
                    appendText(targetComponent, token.getValue(), context);
                    break;

                case ESCAPE:
                    appendText(context.getCurrentComponent(), token.getValue(), context);
                    break;

                case TAG_OPEN:
                    String tagContent = token.getValue();
                    int colonIndex = findFirstColonOutsideQuotes(tagContent);
                    String tagName = colonIndex >= 0 ? tagContent.substring(0, colonIndex) : tagContent;
                    String arguments = colonIndex >= 0 ? tagContent.substring(colonIndex + 1) : "";

                    System.out.println("[Paradigm-Parser] TAG_OPEN - Full content: " + tagContent);
                    System.out.println("[Paradigm-Parser] TAG_OPEN - Tag name: " + tagName);
                    System.out.println("[Paradigm-Parser] TAG_OPEN - Arguments: " + arguments);

                    Tag tag = tagRegistry.getTag(tagName);
                    if (tag != null && tag.canOpen()) {
                        tag.process(context, arguments);
                        tagStack.push(new TagState(tag, arguments));
                    } else {
                        appendText(context.getCurrentComponent(), "<" + tagContent + ">", context);
                    }
                    break;

                case TAG_CLOSE:
                    if (!tagStack.isEmpty()) {
                        TagState state = tagStack.pop();
                        state.tag.close(context);
                    }
                    break;

                case TAG_SELF_CLOSE:
                    String selfCloseTagName = token.getValue();
                    int selfCloseColonIdx = findFirstColonOutsideQuotes(selfCloseTagName);
                    String selfCloseTagBase = selfCloseColonIdx >= 0 ? selfCloseTagName.substring(0, selfCloseColonIdx) : selfCloseTagName;
                    String selfCloseArgs = selfCloseColonIdx >= 0 ? selfCloseTagName.substring(selfCloseColonIdx + 1) : "";

                    Tag selfCloseTag = tagRegistry.getTag(selfCloseTagBase);
                    if (selfCloseTag != null && selfCloseTag.isSelfClosing()) {
                        selfCloseTag.process(context, selfCloseArgs);
                    }
                    break;

                case EOF:
                    break;
            }
        }

        return rootComponent;
    }

    private void appendText(IComponent parent, String text, FormattingContext context) {
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
                IComponent textComponent = platformAdapter.createLiteralComponent(plainText);
                textComponent.setStyle(context.getCurrentStyle());
                parent.append(textComponent);
            }

            if (urlFound) {
                String url = urlMatcher.group(0);
                String fullUrl = url.startsWith("http://") || url.startsWith("https://") ? url : "https://" + url;

                IComponent urlComponent = platformAdapter.createLiteralComponent(url);
                urlComponent.setStyle(context.getCurrentStyle().withClickEvent(
                        new ClickEvent(ClickEvent.Action.OPEN_URL, fullUrl)
                ));
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
                System.out.println("[Paradigm-Parser] findFirstColon: Found colon at position " + i + " in: " + text);
                return i;
            }
        }

        return -1;
    }
}

