package eu.avalanche7.forgeannouncements.utils;

import net.minecraft.util.text.*;
import net.minecraft.util.text.event.ClickEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorUtils {

    private static final Pattern URL_PATTERN = Pattern.compile("http://\\S+|https://\\S+");

    public static ITextComponent parseMessageWithColor(String rawMessage) {
        rawMessage = rawMessage.replace("&", "§");

        ITextComponent message = new TextComponentString("");
        String[] parts = rawMessage.split("(?=§)");
        Style currentStyle = new Style();

        for (String part : parts) {
            if (part.isEmpty()) continue;

            if (part.startsWith("§")) {
                char code = part.charAt(1);
                currentStyle = applyColorCode(new Style(), code);
                part = part.substring(2);
            }
            Matcher urlMatcher = URL_PATTERN.matcher(part);
            int lastEnd = 0;

            while (urlMatcher.find()) {
                message.appendSibling(new TextComponentString(part.substring(lastEnd, urlMatcher.start())).setStyle(currentStyle));
                String url = urlMatcher.group();
                message.appendSibling(new TextComponentString(url)
                        .setStyle(currentStyle.createDeepCopy().setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))));

                lastEnd = urlMatcher.end();
            }
            message.appendSibling(new TextComponentString(part.substring(lastEnd)).setStyle(currentStyle));
        }

        return message;
    }

    private static Style applyColorCode(Style style, char colorCode) {
        switch (colorCode) {
            case '0':
                style = style.setColor(TextFormatting.BLACK);
                break;
            case '1':
                style = style.setColor(TextFormatting.DARK_BLUE);
                break;
            case '2':
                style = style.setColor(TextFormatting.DARK_GREEN);
                break;
            case '3':
                style = style.setColor(TextFormatting.DARK_AQUA);
                break;
            case '4':
                style = style.setColor(TextFormatting.DARK_RED);
                break;
            case '5':
                style = style.setColor(TextFormatting.DARK_PURPLE);
                break;
            case '6':
                style = style.setColor(TextFormatting.GOLD);
                break;
            case '7':
                style = style.setColor(TextFormatting.GRAY);
                break;
            case '8':
                style = style.setColor(TextFormatting.DARK_GRAY);
                break;
            case '9':
                style = style.setColor(TextFormatting.BLUE);
                break;
            case 'a':
                style = style.setColor(TextFormatting.GREEN);
                break;
            case 'b':
                style = style.setColor(TextFormatting.AQUA);
                break;
            case 'c':
                style = style.setColor(TextFormatting.RED);
                break;
            case 'd':
                style = style.setColor(TextFormatting.LIGHT_PURPLE);
                break;
            case 'e':
                style = style.setColor(TextFormatting.YELLOW);
                break;
            case 'f':
                style = style.setColor(TextFormatting.WHITE);
                break;
            case 'k':
                style = style.setObfuscated(true);
                break;
            case 'l':
                style = style.setBold(true);
                break;
            case 'm':
                style = style.setStrikethrough(true);
                break;
            case 'n':
                style = style.setUnderlined(true);
                break;
            case 'o':
                style = style.setItalic(true);
                break;
            case 'r':
                style = new Style(); // Reset
                break;
            default:
                break;
        }
        return style;
    }
}