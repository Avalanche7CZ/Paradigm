package eu.avalanche7.paradigm.api.internal;

import eu.avalanche7.paradigm.api.Registration;
import eu.avalanche7.paradigm.api.RegistrationStatus;

import java.util.concurrent.atomic.AtomicBoolean;

final class SimpleRegistration implements Registration {
    private final String owner;
    private final String key;
    private final RegistrationStatus status;
    private final AtomicBoolean active;
    private final Runnable closeAction;

    SimpleRegistration(String owner, String key, RegistrationStatus status, boolean active, Runnable closeAction) {
        this.owner = owner != null ? owner : "";
        this.key = key != null ? key : "";
        this.status = status;
        this.active = new AtomicBoolean(active);
        this.closeAction = closeAction != null ? closeAction : () -> { };
    }

    static Registration inactive(String owner, String key, RegistrationStatus status) {
        return new SimpleRegistration(owner, key, status, false, null);
    }

    @Override public String ownerModId() { return owner; }
    @Override public String key() { return key; }
    @Override public RegistrationStatus status() { return status; }
    @Override public boolean active() { return active.get(); }

    @Override
    public void close() {
        if (active.compareAndSet(true, false)) closeAction.run();
    }
}
