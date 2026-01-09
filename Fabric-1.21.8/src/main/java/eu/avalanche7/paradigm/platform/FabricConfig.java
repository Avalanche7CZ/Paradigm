package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Fabric implementation of IConfig interface.
 * Uses FabricLoader API to get config directory.
 */
public class FabricConfig implements IConfig {

    private static final String MOD_ID = "paradigm";

    @Override
    public Path getConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public String getModId() {
        return MOD_ID;
    }
}
