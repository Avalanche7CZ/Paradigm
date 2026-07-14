package eu.avalanche7.paradigm.api;

/** Owned registration handle. Closing an active handle removes that owner's registration. */
public interface Registration extends AutoCloseable {
    String ownerModId();
    String key();
    RegistrationStatus status();
    boolean active();

    @Override
    void close();
}
