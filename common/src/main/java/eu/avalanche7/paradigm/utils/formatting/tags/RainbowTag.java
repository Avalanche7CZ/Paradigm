package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;

public class RainbowTag implements Tag {
    private final IPlatformAdapter platformAdapter;
    private float frequency = 0.1f;
    private float saturation = 1.0f;
    private float offset = 0.0f;
    private IComponent rainbowContent;

    public RainbowTag(IPlatformAdapter platformAdapter) {
        this.platformAdapter = platformAdapter;
    }

    @Override
    public String getName() {
        return "rainbow";
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
        parseArguments(arguments);
        rainbowContent = platformAdapter.createComponentFromLiteral("");
        context.pushComponent(rainbowContent);
        context.pushStyle(context.getCurrentStyle());
    }

    @Override
    public void close(FormattingContext context) {
        context.popStyle();

        if (rainbowContent != null) {
            String text = rainbowContent.getRawText();
            if (text != null && !text.isEmpty()) {
                IComponent result = platformAdapter.createComponentFromLiteral("");

                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    float hue = (i * frequency + offset) % 1.0f;
                    int color = hsvToRgb(hue, saturation, 1.0f);

                    IComponent part = platformAdapter.createComponentFromLiteral(String.valueOf(c)).withColor(color);
                    result.append(part);
                }

                context.popComponent();
                context.getCurrentComponent().append(result);
            } else {
                context.popComponent();
            }
        }
    }

    @Override
    public boolean matchesTagName(String name) {
        return name.equalsIgnoreCase("rainbow") || name.equalsIgnoreCase("rb");
    }

    private void parseArguments(String arguments) {
        this.frequency = 0.1f;
        this.saturation = 1.0f;
        this.offset = 0.0f;

        if (arguments == null || arguments.isEmpty()) {
            return;
        }

        String[] parts = arguments.split(":");
        try {
            if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                this.frequency = Float.parseFloat(parts[0].trim());
            }
            if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                this.saturation = Float.parseFloat(parts[1].trim());
            }
            if (parts.length > 2 && !parts[2].trim().isEmpty()) {
                this.offset = Float.parseFloat(parts[2].trim());
            }
        } catch (NumberFormatException e) {
            this.frequency = 0.1f;
            this.saturation = 1.0f;
            this.offset = 0.0f;
        }

        this.frequency = Math.max(0.01f, Math.min(1.0f, frequency));
        this.saturation = Math.max(0.0f, Math.min(1.0f, saturation));
        this.offset = Math.max(0.0f, Math.min(1.0f, offset));
    }

    private int hsvToRgb(float hue, float saturation, float value) {
        float h = hue * 6.0f;
        int i = (int) Math.floor(h);
        float f = h - i;
        float p = value * (1.0f - saturation);
        float q = value * (1.0f - saturation * f);
        float t = value * (1.0f - saturation * (1.0f - f));

        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = value; g = t; b = p; }
            case 1 -> { r = q; g = value; b = p; }
            case 2 -> { r = p; g = value; b = t; }
            case 3 -> { r = p; g = q; b = value; }
            case 4 -> { r = t; g = p; b = value; }
            case 5 -> { r = value; g = p; b = q; }
            default -> { r = g = b = 0; }
        }

        int ri = (int) (r * 255);
        int gi = (int) (g * 255);
        int bi = (int) (b * 255);

        return (ri << 16) | (gi << 8) | bi;
    }
}
