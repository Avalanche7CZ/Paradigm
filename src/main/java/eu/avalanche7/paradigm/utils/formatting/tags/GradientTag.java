package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

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
        gradientContent = new MinecraftComponent(Component.literal(""));
        context.pushComponent(gradientContent);
        context.pushStyle(context.getCurrentStyle());
    }

    @Override
    public void close(FormattingContext context) {
        context.popStyle();

        if (gradientContent != null && colors != null && colors.length >= 2) {
            String text = gradientContent.getRawText();
            if (text != null && !text.isEmpty()) {
                MutableComponent result = Component.literal("");
                int charCount = text.length();

                for (int i = 0; i < charCount; i++) {
                    char c = text.charAt(i);
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

                    Style charStyle = Style.EMPTY.withColor(TextColor.fromRgb(color));
                    result.append(Component.literal(String.valueOf(c)).setStyle(charStyle));
                }

                context.popComponent();
                IComponent parent = context.getCurrentComponent();
                parent.append(new MinecraftComponent(result));
            } else {
                context.popComponent();
            }
        }
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
            if (colorStr.startsWith("#")) {
                try {
                    result[i] = Integer.parseInt(colorStr.substring(1), 16);
                } catch (NumberFormatException e) {
                    result[i] = 0xFFFFFF;
                }
            } else {
                result[i] = 0xFFFFFF;
            }
        }

        return result;
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

