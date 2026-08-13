package eu.avalanche7.paradigm.utils.formatting;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;

@FunctionalInterface
public interface ComponentSlot {

    IComponent render(Object surroundingStyle);
}
