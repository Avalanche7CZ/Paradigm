package eu.avalanche7.paradigm;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;

import java.util.Collections;
import java.util.List;

/**
 * API accessor for Paradigm mod. Implementation is provided by version-specific Paradigm class.
 */
public class ParadigmAPI {
    private static ParadigmAccessor instance;

    public static void setInstance(ParadigmAccessor accessor) {
        instance = accessor;
    }

    public static List<ParadigmModule> getModules() {
        if (instance == null) {
            return Collections.emptyList();
        }
        return instance.getModules();
    }

    public static Services getServices() {
        if (instance == null) {
            return null;
        }
        return instance.getServices();
    }

    public static String getModVersion() {
        if (instance == null) {
            return "unknown";
        }
        return instance.getModVersion();
    }

    /**
     * Interface that version-specific Paradigm class must implement
     */
    public interface ParadigmAccessor {
        List<ParadigmModule> getModules();
        Services getServices();
        String getModVersion();
    }
}

