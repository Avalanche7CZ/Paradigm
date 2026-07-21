package eu.avalanche7.paradigm.modules.holograms;

import java.util.Locale;

public final class HologramDisplaySettings {
    public String billboard = "center";
    public String alignment = "center";
    public double scale = 1.0D;
    public boolean textShadow;
    public String backgroundColor = "#000000";
    public double backgroundOpacity;
    public double textOpacity = 1.0D;
    public boolean seeThrough;
    public int maxLineWidth = 200;

    public HologramDisplaySettings copy() {
        HologramDisplaySettings copy = new HologramDisplaySettings();
        copy.billboard = billboard;
        copy.alignment = alignment;
        copy.scale = scale;
        copy.textShadow = textShadow;
        copy.backgroundColor = backgroundColor;
        copy.backgroundOpacity = backgroundOpacity;
        copy.textOpacity = textOpacity;
        copy.seeThrough = seeThrough;
        copy.maxLineWidth = maxLineWidth;
        return copy;
    }

    public void normalize() {
        billboard = normalizedChoice(billboard, "center", "fixed", "vertical", "horizontal", "center");
        alignment = normalizedChoice(alignment, "center", "left", "center", "right");
        if (!Double.isFinite(scale)) scale = 1.0D;
        scale = clamp(scale, 0.05D, 16.0D);
        backgroundColor = normalizeColor(backgroundColor);
        if (!Double.isFinite(backgroundOpacity)) backgroundOpacity = 0.0D;
        backgroundOpacity = clamp(backgroundOpacity, 0.0D, 1.0D);
        if (!Double.isFinite(textOpacity)) textOpacity = 1.0D;
        textOpacity = clamp(textOpacity, 0.0D, 1.0D);
        maxLineWidth = Math.max(0, Math.min(1024, maxLineWidth));
    }

    public int backgroundArgb() {
        int alpha = (int) Math.round(backgroundOpacity * 255.0D);
        return (alpha << 24) | Integer.parseInt(backgroundColor.substring(1), 16);
    }

    public int textOpacityByte() {
        return (int) Math.round(textOpacity * 255.0D);
    }

    private static String normalizedChoice(String value, String fallback, String... allowed) {
        String normalized = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        for (String candidate : allowed) {
            if (candidate.equals(normalized)) return candidate;
        }
        return fallback;
    }

    private static String normalizeColor(String value) {
        String normalized = value != null ? value.trim() : "";
        if (!normalized.matches("#[0-9a-fA-F]{6}")) return "#000000";
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
