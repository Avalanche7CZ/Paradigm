package eu.avalanche7.paradigm.storage.migration;

public record Migration(
        int version,
        String resourcePath,
        String sql
) {
}
