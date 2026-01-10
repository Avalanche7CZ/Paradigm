package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;

public class GradientTag implements Tag {
    private final IPlatformAdapter platformAdapter;
    private boolean hardGradient = false;
    private int[] colors;
    private IComponent gradientContent;

    public GradientTag(IPlatformAdapter platformAdapter) {
        this.platformAdapter = platformAdapter;
    }

    @Override
    public String getName() {
        return "gradient";
    }

    @Override
    public boolean canOpen() {
        return true;
    }

    @Override
    public boolean canClose() {
        return true;
    }

    @Override
    public void process(FormattingContext context, String arguments) {
        this.hardGradient = getName().equalsIgnoreCase("hard_gradient");
        this.colors = parseColors(arguments);
        gradientContent = platformAdapter.createComponentFromLiteral("");
        context.pushComponent(gradientContent);
        context.pushStyle(context.getCurrentStyle());
    }

    @Override
    public void close(FormattingContext context) {
        Object gradientBaseStyle = context.getCurrentStyle();
        context.popStyle();

        if (gradientContent == null || colors == null || colors.length < 2) {
            if (gradientContent != null) {
                context.popComponent();
            }
            return;
        }

        String fullText = gradientContent.getRawText();
        if (fullText == null || fullText.isEmpty()) {
            context.popComponent();
            return;
        }

        IComponent result = platformAdapter.createComponentFromLiteral("");
        int charCount = fullText.length();

        for (int i = 0; i < charCount; i++) {
            char c = fullText.charAt(i);
            float progress = (float) i / Math.max(1, charCount - 1);

            int color;
            if (hardGradient) {
                int colorIndex = (int) (progress * (colors.length - 1));
                colorIndex = Math.min(colorIndex, colors.length - 1);
                color = colors[colorIndex];
            } else {
                int colorBefore = (int) (progress * (colors.length - 1));
                int colorAfter = Math.min(colorBefore + 1, colors.length - 1);

                if (colorBefore == colorAfter) {
                    color = colors[colorBefore];
                } else {
                    float localProgress = progress * (colors.length - 1) - colorBefore;
                    color = interpolateColor(colors[colorBefore], colors[colorAfter], localProgress);
                }
            }

            IComponent part = platformAdapter.createComponentFromLiteral(String.valueOf(c));
            if (gradientBaseStyle != null) {
                part.setStyle(gradientBaseStyle);
            }
            part = part.withColor(color);
            result.append(part);
        }

        context.popComponent();
        context.getCurrentComponent().append(result);
    }

    @Override
    public boolean matchesTagName(String name) {
        return name.equalsIgnoreCase("gradient") || name.equalsIgnoreCase("gr") ||
               name.equalsIgnoreCase("hard_gradient") || name.equalsIgnoreCase("hgr");
    }

    public int[] parseColors(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new int[]{0xFF0000, 0x00FF00, 0x0000FF};
        }

        String[] colorParts = arguments.split(":");
        int[] result = new int[colorParts.length];

        for (int i = 0; i < colorParts.length; i++) {
            String colorStr = colorParts[i].trim();
            Integer colorRgb = parseColorToRgb(colorStr);
            result[i] = colorRgb != null ? colorRgb : 0xFFFFFF;
        }

        return result;
    }

    private Integer parseColorToRgb(String color) {
        if (color == null || color.isEmpty()) {
            return null;
        }

        String c = color.trim();

        if (c.startsWith("#")) {
            try {
                return Integer.parseInt(c.substring(1), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        if (c.length() == 6) {
            try {
                return Integer.parseInt(c, 16);
            } catch (NumberFormatException ignored) {
            }
        }

        String n = c.toLowerCase(java.util.Locale.ROOT)
                .replace("-", "_")
                .replace(" ", "_");

        n = switch (n) {
            case "darkblue" -> "dark_blue";
            case "darkgreen" -> "dark_green";
            case "darkaqua" -> "dark_aqua";
            case "darkred" -> "dark_red";
            case "darkpurple" -> "dark_purple";
            case "lightpurple" -> "light_purple";
            case "darkgray" -> "dark_gray";
            case "lightgray" -> "gray";
            default -> n;
        };

        return switch (n) {
            case "black" -> 0x000000;
            case "dark_blue" -> 0x0000AA;
            case "dark_green" -> 0x00AA00;
            case "dark_aqua", "dark_cyan" -> 0x00AAAA;
            case "dark_red" -> 0xAA0000;
            case "dark_purple" -> 0xAA00AA;
            case "gold", "orange" -> 0xFFAA00;
            case "gray", "grey" -> 0xAAAAAA;
            case "dark_gray", "dark_grey" -> 0x555555;
            case "blue" -> 0x5555FF;
            case "green" -> 0x55FF55;
            case "aqua", "cyan" -> 0x55FFFF;
            case "red" -> 0xFF5555;
            case "light_purple", "pink", "magenta" -> 0xFF55FF;
            case "yellow" -> 0xFFFF55;
            case "white" -> 0xFFFFFF;
            default -> null;
        };
    }

    private int interpolateColor(int color1, int color2, float progress) {
        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;

        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;

        int r = (int) (r1 + (r2 - r1) * progress);
        int g = (int) (g1 + (g2 - g1) * progress);
        int b = (int) (b1 + (b2 - b1) * progress);

        return (r << 16) | (g << 8) | b;
    }
}
