package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageParser {

    private final Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private final Pattern urlPattern = Pattern.compile("https?://\\S+");
    private final Map<Pattern, BiConsumer<Matcher, TagContext>> tagHandlers;
    private final Placeholders placeholders;
    private final IPlatformAdapter platformAdapter;

    public MessageParser(Placeholders placeholders, IPlatformAdapter platformAdapter) {
        this.placeholders = placeholders;
        this.platformAdapter = platformAdapter;
        this.tagHandlers = new LinkedHashMap<>();
        initializeTagHandlers();
    }

    private void initializeTagHandlers() {
        tagHandlers.put(Pattern.compile("\\[link=(.*?)]"), (matcher, context) -> {
            String url = matcher.group(1);
            String formattedUrl = formatUrl(url);
            IComponent clickable = platformAdapter.createComponentFromLiteral(url)
                    .withStyle(context.getCurrentStyle())
                    .onClickOpenUrl(formattedUrl);
            context.getText().append(clickable);
            context.getText().append(platformAdapter.createComponentFromLiteral(" ").withStyle(context.getCurrentStyle()));
        });

        tagHandlers.put(Pattern.compile("\\[command=(.*?)]"), (matcher, context) -> {
            String command = matcher.group(1);
            String fullCommand = command.startsWith("/") ? command : "/" + command;
            IComponent clickable = platformAdapter.createComponentFromLiteral(fullCommand)
                    .withStyle(context.getCurrentStyle())
                    .onClickRunCommand(fullCommand);
            context.getText().append(clickable);
            context.getText().append(platformAdapter.createComponentFromLiteral(" ").withStyle(context.getCurrentStyle()));
        });

        tagHandlers.put(Pattern.compile("\\[hover=(.*?)](.*?)\\[/hover]", Pattern.DOTALL), (matcher, context) -> {
            String hoverTextContent = matcher.group(1);
            String mainTextContent = matcher.group(2);
            IComponent hoverComponent = parseMessageInternal(mainTextContentOf(hoverTextContent), context.getPlayer(), Style.EMPTY);
            IComponent textWithHover = platformAdapter.createEmptyComponent();
            parseTextRecursive(mainTextContent, textWithHover, context.getCurrentStyle(), context.getPlayer());
            IComponent hovered = textWithHover.onHoverComponent(hoverComponent);
            context.getText().append(hovered);
        });

        tagHandlers.put(Pattern.compile("\\[divider]"), (matcher, context) -> {
            IComponent divider = platformAdapter.createComponentFromLiteral("--------------------")
                    .withStyle(context.getCurrentStyle())
                    .withFormatting(Formatting.GRAY);
            context.getText().append(divider);
        });

        tagHandlers.put(Pattern.compile("\\[title=(.*?)]", Pattern.DOTALL), (matcher, context) -> {
            ServerPlayerEntity sp = context.getOriginalPlayer();
            if (sp != null) {
                String titleText = matcher.group(1);
                IComponent titleComponent = parseMessageInternal(titleText, context.getPlayer(), context.getCurrentStyle());
                platformAdapter.clearTitles(sp);
                platformAdapter.sendTitle(sp, titleComponent.getOriginalText(), platformAdapter.createComponentFromLiteral("").getOriginalText());
            }
        });

        tagHandlers.put(Pattern.compile("\\[subtitle=(.*?)]", Pattern.DOTALL), (matcher, context) -> {
            ServerPlayerEntity sp = context.getOriginalPlayer();
            if (sp != null) {
                String subtitleText = matcher.group(1);
                IComponent subtitleComponent = parseMessageInternal(subtitleText, context.getPlayer(), context.getCurrentStyle());
                platformAdapter.sendSubtitle(sp, subtitleComponent.getOriginalText());
            }
        });

        tagHandlers.put(Pattern.compile("\\[center](.*?)\\[/center]", Pattern.DOTALL), (matcher, context) -> {
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
                context.getText().append(platformAdapter.createComponentFromLiteral(paddingSpaces).withStyle(context.getCurrentStyle()));
            }
            context.getText().append(innerComponent);
        });
    }

    public IComponent parseMessage(String rawMessage, IPlayer player) {
        return parseMessageInternal(rawMessage, player, Style.EMPTY);
    }

    private IComponent parseMessageInternal(String rawMessage, IPlayer player, Style initialStyle) {
        if (rawMessage == null) {
            return platformAdapter.createComponentFromLiteral("").withStyle(initialStyle);
        }

        String processedMessage = this.placeholders.replacePlaceholders(rawMessage,
                player != null ? player.getOriginalPlayer() : null);

        Matcher hexMatcher = hexPattern.matcher(processedMessage);
        StringBuilder sb = new StringBuilder();
        while (hexMatcher.find()) {
            String hexColor = hexMatcher.group(1);
            hexMatcher.appendReplacement(sb, "§#" + hexColor);
        }
        hexMatcher.appendTail(sb);
        processedMessage = sb.toString();
        String messageForParsing = processedMessage.replace("&", "§");

        IComponent rootComponent = platformAdapter.createEmptyComponent();
        parseTextRecursive(messageForParsing, rootComponent, initialStyle, player);
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
                String plain = textToParse.substring(currentIndex, firstEventIndex);
                parentComponent.append(platformAdapter.createComponentFromLiteral(plain).withStyle(currentStyle));
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
                                parentComponent.append(platformAdapter.createComponentFromLiteral(textToParse.substring(currentIndex, currentIndex + 2)).withStyle(currentStyle));
                                currentIndex += 2;
                            }
                        } else {
                            parentComponent.append(platformAdapter.createComponentFromLiteral(textToParse.substring(currentIndex, currentIndex + 1)).withStyle(currentStyle));
                            currentIndex += 1;
                        }
                    } else {
                        Formatting format = Formatting.byCode(formatChar);
                        if (format != null) {
                            currentStyle = currentStyle.withFormatting(format);
                            if (format == Formatting.RESET) currentStyle = Style.EMPTY;
                        } else {
                            parentComponent.append(platformAdapter.createComponentFromLiteral("§").withStyle(currentStyle));
                        }
                        currentIndex += 2;
                    }
                } else {
                    parentComponent.append(platformAdapter.createComponentFromLiteral("§").withStyle(currentStyle));
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
                    parentComponent.append(platformAdapter.createComponentFromLiteral("[").withStyle(currentStyle));
                    currentIndex += 1;
                }
            } else if (nextUrlFound && nextUrlStart == currentIndex) {
                String url = urlMatcher.group(0);
                String formattedUrl = formatUrl(url);
                IComponent urlComp = platformAdapter.createComponentFromLiteral(url)
                        .withStyle(currentStyle)
                        .onClickOpenUrl(formattedUrl);
                parentComponent.append(urlComp);
                currentIndex = urlMatcher.end();
            } else {
                if (currentIndex < length) {
                    parentComponent.append(platformAdapter.createComponentFromLiteral(textToParse.substring(currentIndex, currentIndex + 1)).withStyle(currentStyle));
                    currentIndex += 1;
                }
            }
        }
    }

    private String formatUrl(String url) {
        if (url != null && !url.toLowerCase().startsWith("http://") && !url.toLowerCase().startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }

    private String mainTextContentOf(String s) {
        return s == null ? "" : s;
    }

    private static class TagContext {
        private final IComponent text;
        private final Style currentStyle;
        private final IPlayer player;

        TagContext(IComponent text, Style style, IPlayer player) {
            this.text = text;
            this.currentStyle = style;
            this.player = player;
        }

        public IComponent getText() { return text; }
        public Style getCurrentStyle() { return currentStyle; }
        public IPlayer getPlayer() { return player; }
        public ServerPlayerEntity getOriginalPlayer() { return player != null ? player.getOriginalPlayer() : null; }
    }
}
