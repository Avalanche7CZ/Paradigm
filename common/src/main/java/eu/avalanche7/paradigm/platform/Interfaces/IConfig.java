package eu.avalanche7.paradigm.platform.Interfaces;

import java.nio.file.Path;

/**
 * Platform-agnostic interface for configuration path resolution.
 * Each platform (Fabric/Forge) provides its own implementation.
 */
public interface IConfig {

    /**
     * Get the configuration directory path for this platform.
     *
     * @return Path to the config directory (e.g., config/ folder)
     */
    Path getConfigDirectory();

    /**
     * Get the mod ID for logging and path resolution.
     *
     * @return The mod identifier string
     */
    String getModId();

    /**
     * Resolve a config file path relative to the config directory.
     *
     * @param relativePath Path relative to config dir (e.g., "paradigm/chat.json")
     * @return Full resolved path
     */
    default Path resolveConfigPath(String relativePath) {
        return getConfigDirectory().resolve(relativePath);
    }
}
