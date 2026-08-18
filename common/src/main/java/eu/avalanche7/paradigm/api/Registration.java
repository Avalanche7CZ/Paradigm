package eu.avalanche7.paradigm.api;

public interface Registration extends AutoCloseable {
    String ownerModId();
    String key();
    RegistrationStatus status();
    boolean active();

    @Override
    void close();
}
