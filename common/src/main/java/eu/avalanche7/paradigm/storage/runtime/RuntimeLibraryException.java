package eu.avalanche7.paradigm.storage.runtime;

import eu.avalanche7.paradigm.storage.StorageException;

public class RuntimeLibraryException extends StorageException {
    public RuntimeLibraryException(String message) {
        super(message);
    }

    public RuntimeLibraryException(String message, Throwable cause) {
        super(message, cause);
    }
}
