package eu.avalanche7.paradigm.api;

/** Registration surface for namespaced external placeholders. */
public interface PlaceholderService {
    Registration register(String ownerModId, String placeholderKey, ExternalPlaceholderResolver resolver);
}
