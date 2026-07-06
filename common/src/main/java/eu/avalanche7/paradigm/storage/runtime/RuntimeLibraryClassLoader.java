package eu.avalanche7.paradigm.storage.runtime;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

public class RuntimeLibraryClassLoader extends URLClassLoader {
    public RuntimeLibraryClassLoader(Path jarPath) throws Exception {
        super(new URL[]{jarPath.toUri().toURL()}, RuntimeLibraryClassLoader.class.getClassLoader());
    }
}
