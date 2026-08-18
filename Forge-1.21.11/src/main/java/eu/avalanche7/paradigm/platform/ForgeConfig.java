package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

public class ForgeConfig implements IConfig {

    private static final String MOD_ID = "paradigm";

    @Override
    public Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public String getModId() {
        return MOD_ID;
    }
}
