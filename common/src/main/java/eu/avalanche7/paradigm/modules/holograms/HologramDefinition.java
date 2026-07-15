package eu.avalanche7.paradigm.modules.holograms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class HologramDefinition {
    public boolean enabled = true;
    public String dimension = "minecraft:overworld";
    public double x;
    public double y;
    public double z;
    public Double viewDistance;
    public Integer refreshIntervalSeconds;
    public double lineSpacing = 0.28D;
    public List<String> lines = new ArrayList<>();

    public HologramDefinition copy() {
        HologramDefinition copy = new HologramDefinition();
        copy.enabled = enabled;
        copy.dimension = dimension;
        copy.x = x;
        copy.y = y;
        copy.z = z;
        copy.viewDistance = viewDistance;
        copy.refreshIntervalSeconds = refreshIntervalSeconds;
        copy.lineSpacing = lineSpacing;
        copy.lines = lines != null ? new ArrayList<>(lines) : new ArrayList<>();
        return copy;
    }

    public void normalize(double defaultViewDistance, int defaultRefreshIntervalSeconds) {
        if (dimension == null || dimension.isBlank()) dimension = "minecraft:overworld";
        if (!Double.isFinite(x)) x = 0.5D;
        if (!Double.isFinite(y)) y = 64.0D;
        if (!Double.isFinite(z)) z = 0.5D;
        if (viewDistance == null || !Double.isFinite(viewDistance) || viewDistance < 1.0D) viewDistance = defaultViewDistance;
        viewDistance = Math.min(512.0D, viewDistance);
        if (refreshIntervalSeconds == null || refreshIntervalSeconds < 1) refreshIntervalSeconds = defaultRefreshIntervalSeconds;
        refreshIntervalSeconds = Math.min(3600, refreshIntervalSeconds);
        if (!Double.isFinite(lineSpacing) || lineSpacing < 0.05D) lineSpacing = 0.28D;
        lineSpacing = Math.min(4.0D, lineSpacing);
        if (lines == null) lines = new ArrayList<>();
        lines.removeIf(Objects::isNull);
    }
}
