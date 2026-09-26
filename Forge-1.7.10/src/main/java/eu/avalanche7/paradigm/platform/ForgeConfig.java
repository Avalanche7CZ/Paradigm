package eu.avalanche7.paradigm.platform;

import java.nio.file.Path;
import java.util.Objects;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;

public final class ForgeConfig implements IConfig {
    private final Path configDirectory;

    public ForgeConfig(Path configDirectory) {
        this.configDirectory = Objects.requireNonNull(configDirectory, "configDirectory");
    }

    @Override
    public Path getConfigDirectory() {
        return configDirectory;
    }

    @Override
    public String getModId() {
        return "paradigm";
    }
}
