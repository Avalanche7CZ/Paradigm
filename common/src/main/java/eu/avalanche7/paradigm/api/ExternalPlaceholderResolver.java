package eu.avalanche7.paradigm.api;

@FunctionalInterface
public interface ExternalPlaceholderResolver {
    String resolve(PlaceholderContext context);
}
