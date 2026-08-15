package eu.avalanche7.paradigm.modules.discord.console;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MinecraftAnsi {
    private static final String ESC = "\u001b";
    private static final String RESET = ESC + "[0m";

    private static final Pattern CODE = Pattern.compile(
            "§x(?:§[0-9a-fA-F]){6}"
            + "|[§&]#[0-9a-fA-F]{6}"
            + "|[§&][0-9a-fA-Fk-oK-OrR]");

    private static final int[] PALETTE_RGB = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private static final String[] PALETTE_ANSI = {
        "30", "34", "32", "36", "31", "35", "33", "37",
        "1;30", "1;34", "1;32", "1;36", "1;31", "1;35", "1;33", "1;37"
    };

    private MinecraftAnsi() {
    }

    static String translate(String text, String baseColor) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = CODE.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            out.append(toAnsi(matcher.group(), baseColor));
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    private static String toAnsi(String code, String baseColor) {
        if (code.length() == 14) {
            return color(nearest(parseHex(code.substring(2).replace("§", ""))));
        }
        if (code.length() == 8) {
            return color(nearest(parseHex(code.substring(2))));
        }

        char id = Character.toLowerCase(code.charAt(1));
        int index = "0123456789abcdef".indexOf(id);
        if (index >= 0) {
            return color(index);
        }
        return switch (id) {
            case 'l' -> ESC + "[1m";
            case 'n' -> ESC + "[4m";
            case 'r' -> RESET + baseColor;
            default -> "";
        };
    }

    private static String color(int index) {
        return ESC + "[" + PALETTE_ANSI[index] + "m";
    }

    private static int parseHex(String hex) {
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException invalid) {
            return 0xFFFFFF;
        }
    }

    private static int nearest(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        int best = 15;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < PALETTE_RGB.length; i++) {
            int dr = red - ((PALETTE_RGB[i] >> 16) & 0xFF);
            int dg = green - ((PALETTE_RGB[i] >> 8) & 0xFF);
            int db = blue - (PALETTE_RGB[i] & 0xFF);
            long distance = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}
