package eu.avalanche7.paradigm.modules.holograms;

public record HologramLine(int index, String template, boolean dynamic) {
    public static HologramLine of(int index, String template) {
        String value = template != null ? template : "";
        return new HologramLine(index, value, value.indexOf('{') >= 0);
    }
}
