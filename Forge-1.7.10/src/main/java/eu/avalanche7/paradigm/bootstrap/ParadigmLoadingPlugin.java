package eu.avalanche7.paradigm.bootstrap;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions("eu.avalanche7.paradigm.bootstrap")
public final class ParadigmLoadingPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {
    @Override
    public String getMixinConfig() {
        return "paradigm.mixins.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedCoreMods) {
        return List.of("NetHandlerLoginServerMixin", "ServerConfigurationManagerMixin", "NetHandlerPlayServerMixin", "EntityTrackerAccess", "PlayerListPacketAccess", "EntityTrackerEntryMixin", "ServerStatusMixin", "ClientCapabilitiesMixin", "MenuNetworkMixin", "CloseWindowPacketAccess", "UseEntityPacketAccess", "HologramInteractionMixin");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
