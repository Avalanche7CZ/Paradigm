package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.MinecraftComponent;

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
        tagHandlers.put(Pattern.compile("\\[link=([^\\]]+)\\]"), (matcher, context) -> {
            if (platformAdapter == null) return;
            String url = matcher.group(1);
            IComponent urlComponent = new MinecraftComponent(platformAdapter.createLiteralComponent(url));
            urlComponent = urlComponent.onClickOpenUrl(formatUrl(url));
            urlComponent = platformAdapter.parseFormattingCode("b", urlComponent);
            context.getComponent().append(urlComponent);
        });

        tagHandlers.put(Pattern.compile("\\[command=([^\\]]+)\\]"), (matcher, context) -> {
            if (platformAdapter == null) return;
            String command = matcher.group(1);
            String displayText = command.startsWith("/") ? command : command;
            String fullCommand = command.startsWith("/") ? command : "/" + command;
            IComponent cmdComponent = new MinecraftComponent(platformAdapter.createLiteralComponent(displayText));
            cmdComponent = cmdComponent.onClickRunCommand(fullCommand);
            cmdComponent = platformAdapter.parseFormattingCode("b", cmdComponent);
            context.getComponent().append(cmdComponent);
        });

        tagHandlers.put(Pattern.compile("\\[hover=(.*?)\\](.*?)\\[/hover\\]", Pattern.DOTALL), (matcher, context) -> {
            if (platformAdapter == null) return;
            String hoverTextContent = matcher.group(1);
            String mainTextContent = matcher.group(2);
            IComponent hoverComponent = parseMessageInternal(hoverTextContent, context.getPlayer());
            IComponent textWithHover = parseMessageInternal(mainTextContent, context.getPlayer());
            textWithHover = textWithHover.onHoverComponent(hoverComponent);
            context.getComponent().append(textWithHover);
        });

        tagHandlers.put(Pattern.compile("\\[divider\\]"), (matcher, context) -> {
            if (platformAdapter == null) return;
            String dividerLine = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";
            IComponent dividerComponent = new MinecraftComponent(platformAdapter.createLiteralComponent(dividerLine));
            dividerComponent = dividerComponent.withColor("gray");
            context.getComponent().append(dividerComponent);
        });

        tagHandlers.put(Pattern.compile("\\[title=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (platformAdapter == null || context.getPlayer() == null) return;
            String titleText = matcher.group(1);
            IComponent titleComponent = parseMessageInternal(titleText, context.getPlayer());
            if (titleComponent instanceof MinecraftComponent mc) {
                platformAdapter.clearTitles(context.getPlayer().getOriginalPlayer());
                platformAdapter.sendTitle(context.getPlayer().getOriginalPlayer(), mc.getHandle(),
                    platformAdapter.createLiteralComponent(""));
            }
        });

        tagHandlers.put(Pattern.compile("\\[subtitle=(.*?)\\]", Pattern.DOTALL), (matcher, context) -> {
            if (platformAdapter == null || context.getPlayer() == null) return;
            String subtitleText = matcher.group(1);
            IComponent subtitleComponent = parseMessageInternal(subtitleText, context.getPlayer());
            if (subtitleComponent instanceof MinecraftComponent mc) {
                platformAdapter.sendSubtitle(context.getPlayer().getOriginalPlayer(), mc.getHandle());
            }
        });

        tagHandlers.put(Pattern.compile("\\[center\\](.*?)\\[/center\\]", Pattern.DOTALL), (matcher, context) -> {
            if (platformAdapter == null) return;
            String textToCenter = matcher.group(1);
            IComponent innerComponent = parseMessageInternal(textToCenter, context.getPlayer());
            String plainInnerText = innerComponent.getRawText();
            int approximateChatWidthChars = 53;
            int textLength = plainInnerText.length();
            if (textLength < approximateChatWidthChars) {
                int totalPadding = approximateChatWidthChars - textLength;
                int leftPadding = totalPadding / 2;
                if (leftPadding > 0) {
                    String paddingSpaces = " ".repeat(leftPadding);
                    IComponent padComponent = new MinecraftComponent(platformAdapter.createLiteralComponent(paddingSpaces));
                    context.getComponent().append(padComponent);
                }
            }
            context.getComponent().append(innerComponent);
        });
    }

    public IComponent parseMessage(String rawMessage, IPlayer player) {
        return parseMessageInternal(rawMessage, player);
    }

    private IComponent parseMessageInternal(String rawMessage, IPlayer player) {
        if (rawMessage == null) {
            return platformAdapter != null ? platformAdapter.createEmptyComponent() : null;
        }

        if (platformAdapter == null) {
            return platformAdapter.createEmptyComponent();
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
        parseTextRecursive(messageForParsing, rootComponent, player);
        return rootComponent;
    }

    private void parseTextRecursive(String textToParse, IComponent parentComponent, IPlayer player) {
        if (platformAdapter == null) return;

        int currentIndex = 0;
        int length = textToParse.length();
        StringBuilder plainTextBuffer = new StringBuilder();
        String currentColor = null;
        String currentFormatting = null;

        while (currentIndex < length) {
            int nextFormatCode = textToParse.indexOf('§', currentIndex);
            int nextTagStart = textToParse.indexOf('[', currentIndex);

            Matcher urlMatcher = urlPattern.matcher(textToParse);
            boolean nextUrlFound = urlMatcher.find(currentIndex);
            int nextUrlStart = nextUrlFound ? urlMatcher.start() : -1;

            int firstEventIndex = length;
            if (nextFormatCode != -1) firstEventIndex = Math.min(firstEventIndex, nextFormatCode);
            if (nextTagStart != -1) firstEventIndex = Math.min(firstEventIndex, nextTagStart);
            if (nextUrlFound) firstEventIndex = Math.min(firstEventIndex, nextUrlStart);

            if (firstEventIndex > currentIndex) {
                plainTextBuffer.append(textToParse, currentIndex, firstEventIndex);
            }

            currentIndex = firstEventIndex;
            if (currentIndex == length) break;

            if (nextFormatCode == currentIndex) {
                if (currentIndex + 1 < length) {
                    char formatChar = textToParse.charAt(currentIndex + 1);

                    if (formatChar == '#') {
                        if (currentIndex + 8 <= length) {
                            if (plainTextBuffer.length() > 0) {
                                appendStyledText(parentComponent, plainTextBuffer.toString(), currentColor, currentFormatting);
                                plainTextBuffer.setLength(0);
                            }
                            String hex = textToParse.substring(currentIndex + 2, currentIndex + 8);
                            currentColor = hex;
                            currentFormatting = null;
                            currentIndex += 8;
                        } else {
                            plainTextBuffer.append("§#");
                            currentIndex += 2;
                        }
                    } else if (formatChar == 'r') {
                        if (plainTextBuffer.length() > 0) {
                            appendStyledText(parentComponent, plainTextBuffer.toString(), currentColor, currentFormatting);
                            plainTextBuffer.setLength(0);
                        }
                        currentColor = null;
                        currentFormatting = null;
                        currentIndex += 2;
                    } else {
                        net.minecraft.util.Formatting format = net.minecraft.util.Formatting.byCode(formatChar);
                        if (format != null) {
                            if (plainTextBuffer.length() > 0) {
                                appendStyledText(parentComponent, plainTextBuffer.toString(), currentColor, currentFormatting);
                                plainTextBuffer.setLength(0);
                            }

                            if (format.isColor()) {
                                currentColor = String.valueOf(formatChar);
                                currentFormatting = null;
                            } else {
                                currentFormatting = String.valueOf(formatChar);
                            }
                        } else {
                            plainTextBuffer.append('§').append(formatChar);
                        }
                        currentIndex += 2;
                    }
                } else {
                    plainTextBuffer.append('§');
                    currentIndex += 1;
                }
            } else if (nextTagStart == currentIndex) {
                if (plainTextBuffer.length() > 0) {
                    appendStyledText(parentComponent, plainTextBuffer.toString(), currentColor, currentFormatting);
                    plainTextBuffer.setLength(0);
                }

                boolean tagHandled = false;
                for (Map.Entry<Pattern, BiConsumer<Matcher, TagContext>> entry : tagHandlers.entrySet()) {
                    Pattern tagPattern = entry.getKey();
                    Matcher tagMatcher = tagPattern.matcher(textToParse);
                    if (tagMatcher.find(currentIndex) && tagMatcher.start() == currentIndex) {
                        TagContext context = new TagContext(parentComponent, player);
                        entry.getValue().accept(tagMatcher, context);
                        currentIndex = tagMatcher.end();
                        tagHandled = true;
                        break;
                    }
                }
                if (!tagHandled) {
                    plainTextBuffer.append('[');
                    currentIndex += 1;
                }
            } else if (nextUrlFound && nextUrlStart == currentIndex) {
                if (plainTextBuffer.length() > 0) {
                    appendStyledText(parentComponent, plainTextBuffer.toString(), currentColor, currentFormatting);
                    plainTextBuffer.setLength(0);
                }

                String url = urlMatcher.group(0);
                IComponent urlComp = new MinecraftComponent(platformAdapter.createLiteralComponent(url));
                urlComp = urlComp.onClickOpenUrl(formatUrl(url));
                urlComp = platformAdapter.parseFormattingCode("b", urlComp);
                parentComponent.append(urlComp);
                currentIndex = urlMatcher.end();
            } else {
                if (currentIndex < length) {
                    plainTextBuffer.append(textToParse.charAt(currentIndex));
                    currentIndex += 1;
                }
            }
        }

        if (plainTextBuffer.length() > 0) {
            appendStyledText(parentComponent, plainTextBuffer.toString(), currentColor, currentFormatting);
        }
    }

    private void appendStyledText(IComponent parentComponent, String text, String color, String formatting) {
        if (text.isEmpty()) return;

        IComponent textComp = new MinecraftComponent(platformAdapter.createLiteralComponent(text));

        if (color != null) {
            if (color.length() == 6) {
                textComp = platformAdapter.parseHexColor(color, textComp);
            } else if (color.length() == 1) {
                textComp = platformAdapter.parseFormattingCode(color, textComp);
            }
        }

        if (formatting != null && formatting.length() == 1) {
            textComp = platformAdapter.parseFormattingCode(formatting, textComp);
        }

        parentComponent.append(textComp);
    }

    private String formatUrl(String url) {
        if (url != null && !url.toLowerCase().startsWith("http://") && !url.toLowerCase().startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }

    private static class TagContext {
        private final IComponent component;
        private final IPlayer player;

        public TagContext(IComponent component, IPlayer player) {
            this.component = component;
            this.player = player;
        }

        public IComponent getComponent() {
            return component;
        }

        public IPlayer getPlayer() {
            return player;
        }
    }
}
