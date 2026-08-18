package eu.avalanche7.paradigm.platform.Interfaces;

import java.nio.file.Path;

public interface IConfig {

    Path getConfigDirectory();

    String getModId();

    default Path resolveConfigPath(String relativePath) {
        return getConfigDirectory().resolve(relativePath);
    }
}
