package eu.avalanche7.paradigm.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class ModuleLifecycle {
    private enum State {
        ENABLING, ACTIVE, DISABLING, INACTIVE, CLEANUP_PENDING
    }

    private record Transition(State state) {}

    private final Services services;
    private final Map<ParadigmModule, Transition> states = Collections.synchronizedMap(new IdentityHashMap<>());
    private volatile boolean stopping;

    ModuleLifecycle(Services services) {
        this.services = services;
    }

    boolean isStopping() {
        return stopping;
    }

    synchronized void setStopping(boolean stopping) {
        this.stopping = stopping;
    }

    synchronized boolean beginStopping() {
        if (stopping) return false;
        stopping = true;
        return true;
    }

    boolean isActive(ParadigmModule module) {
        return state(module) == State.ACTIVE;
    }

    boolean canDispatchEvents(ParadigmModule module) {
        State state = state(module);
        return !stopping && (state == null || state == State.ACTIVE);
    }

    BooleanSupplier activationGuard(ParadigmModule module) {
        Transition expected = states.get(module);
        boolean allowed = (expected == null || expected.state() == State.ACTIVE) && !stopping;
        return () -> allowed && !stopping && states.get(module) == expected && module.isEnabled(services);
    }

    private State state(ParadigmModule module) {
        Transition transition = states.get(module);
        return transition == null ? null : transition.state();
    }

    private void setState(ParadigmModule module, State state) {
        states.put(module, new Transition(state));
    }

    synchronized boolean reconcile(ParadigmModule module) {
        State state = state(module);
        if (stopping || state == State.ENABLING || state == State.DISABLING) return false;
        if (module.isEnabled(services)) return state != State.ACTIVE && activate(module, state);
        if (state != State.ACTIVE && state != State.CLEANUP_PENDING) return false;
        cleanup(module);
        return true;
    }

    synchronized void enable(ParadigmModule module) {
        State state = state(module);
        if (stopping || state == State.ENABLING || state == State.ACTIVE || state == State.DISABLING
                || !module.isEnabled(services)) return;
        activate(module, state);
    }

    private boolean activate(ParadigmModule module, State state) {
        if (state == State.CLEANUP_PENDING) {
            cleanup(module);
            if (stopping || !module.isEnabled(services)) return true;
        }
        setState(module, State.ENABLING);
        boolean stillEnabled;
        try {
            module.onEnable(services);
            stillEnabled = !stopping && module.isEnabled(services);
        } catch (RuntimeException | Error failure) {
            try {
                cleanup(module);
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        if (stillEnabled) {
            setState(module, State.ACTIVE);
        } else {
            cleanup(module);
        }
        return true;
    }

    synchronized void disable(ParadigmModule module) {
        State state = state(module);
        if (state == State.ACTIVE || state == State.CLEANUP_PENDING) {
            cleanup(module);
        } else if (state == null) {
            setState(module, State.INACTIVE);
        }
    }

    synchronized void stop(ParadigmModule module, Object event) {
        State state = state(module);
        if (state != State.ACTIVE && state != State.CLEANUP_PENDING) return;
        if (state == State.ACTIVE) {
            setState(module, State.DISABLING);
            try {
                module.onServerStopping(event, services);
            } catch (RuntimeException | Error failure) {
                if (services.getLogger() != null) {
                    services.getLogger().warn("Failed to stop module {}; disable cleanup will continue.", module.getName(), failure);
                }
            }
        }
        try {
            cleanup(module);
        } catch (RuntimeException | Error failure) {
            if (services.getLogger() != null) {
                services.getLogger().warn("Failed to disable module {} during shutdown.", module.getName(), failure);
            }
        }
    }

    synchronized void stopRemaining() {
        for (ParadigmModule module : List.copyOf(states.keySet())) {
            stop(module, null);
        }
    }

    private void cleanup(ParadigmModule module) {
        setState(module, State.DISABLING);
        try {
            module.onDisable(services);
            setState(module, State.INACTIVE);
        } catch (RuntimeException | Error failure) {
            setState(module, State.CLEANUP_PENDING);
            throw failure;
        }
    }
}
