package eu.avalanche7.paradigm.api;

/** Non-blocking read-only resolver for one externally owned placeholder. */
@FunctionalInterface
public interface ExternalPlaceholderResolver {
    String resolve(PlaceholderContext context);
}
