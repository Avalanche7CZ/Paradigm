package eu.avalanche7.paradigm.storage.runtime;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

public class RuntimeLibraryClassLoader extends URLClassLoader {
    private final String driverPackage;
    private final String resourcePrefix;

    RuntimeLibraryClassLoader(Path jarPath, String primaryClass) throws Exception {
        super(new URL[]{jarPath.toUri().toURL()}, RuntimeLibraryClassLoader.class.getClassLoader());
        driverPackage = primaryClass.substring(0, primaryClass.lastIndexOf('.') + 1);
        resourcePrefix = driverPackage.replace('.', '/');
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith(driverPackage)) return super.loadClass(name, resolve);
        Class<?> type = findLoadedClass(name);
        if (type == null) type = findClass(name);
        if (resolve) resolveClass(type);
        return type;
    }

    @Override
    public URL getResource(String name) {
        return name.startsWith(resourcePrefix) ? findResource(name) : super.getResource(name);
    }
}
