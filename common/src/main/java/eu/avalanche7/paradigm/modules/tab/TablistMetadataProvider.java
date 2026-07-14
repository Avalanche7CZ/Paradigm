package eu.avalanche7.paradigm.modules.tab;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

@FunctionalInterface
public interface TablistMetadataProvider {
    TablistMetadata resolve(IPlayer player);
}
