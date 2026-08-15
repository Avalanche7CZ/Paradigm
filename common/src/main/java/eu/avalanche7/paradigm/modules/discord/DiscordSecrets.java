package eu.avalanche7.paradigm.modules.discord;

import java.util.Locale;

public final class DiscordSecrets {
    public static final String PLACEHOLDER = "<redacted>";

    private DiscordSecrets() {
    }

    public static String redact(String text, String token) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        if (token != null && token.length() >= 8) {
            result = result.replace(token, PLACEHOLDER);

            int firstDot = token.indexOf('.');
            if (firstDot >= 8) {
                result = result.replace(token.substring(0, firstDot), PLACEHOLDER);
            }
        }
        return stripBearerLikeTokens(result);
    }

    private static String stripBearerLikeTokens(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("bot ");
        if (index < 0) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text.length());
        int cursor = 0;
        while (index >= 0) {
            int end = index + "bot ".length();
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            if (end - (index + 4) >= 8) {
                builder.append(text, cursor, index + "bot ".length()).append(PLACEHOLDER);
                cursor = end;
            } else {
                builder.append(text, cursor, end);
                cursor = end;
            }
            index = lower.indexOf("bot ", cursor);
        }
        builder.append(text, cursor, text.length());
        return builder.toString();
    }

    public static String mask(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        return "•".repeat(12);
    }

    public static boolean isPresent(String token) {
        return token != null && !token.isBlank();
    }
}
