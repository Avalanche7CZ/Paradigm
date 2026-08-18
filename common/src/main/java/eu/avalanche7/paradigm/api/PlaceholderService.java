package eu.avalanche7.paradigm.api;

public interface PlaceholderService {
    Registration register(String ownerModId, String placeholderKey, ExternalPlaceholderResolver resolver);
}
