package eu.avalanche7.paradigm.modules.tab;

public record TablistMetadata(String group, String prefix, String suffix, int groupWeight, Source source, String nameFormat) {
    public static final TablistMetadata EMPTY = new TablistMetadata("", "", "", 0, Source.NONE);

    public TablistMetadata(String group, String prefix, String suffix, int groupWeight, Source source) {
        this(group, prefix, suffix, groupWeight, source, "");
    }

    public TablistMetadata {
        group = group != null ? group : "";
        prefix = prefix != null ? prefix : "";
        suffix = suffix != null ? suffix : "";
        source = source != null ? source : Source.NONE;
        nameFormat = nameFormat != null ? nameFormat : "";
    }

    public enum Source { PARADIGM, LUCKPERMS, NONE }
}
