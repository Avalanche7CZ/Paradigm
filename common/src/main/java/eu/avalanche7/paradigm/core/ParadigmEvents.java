package eu.avalanche7.paradigm.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;

public final class ParadigmEvents {
    public interface Listener {
        default void onPunishmentCreated(PunishmentRecord record) {
        }

        default void onPunishmentRevoked(PunishmentRecord record) {
        }

        default void onRestartScheduled(long restartAtEpochMs) {
        }

        default void onRestartCancelled() {
        }

        default void onRestartCountdown(long secondsRemaining) {
        }

        default void onRestartImminent() {
        }
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void register(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unregister(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public int listenerCount() {
        return listeners.size();
    }

    public void punishmentCreated(PunishmentRecord record) {
        if (record != null) {
            dispatch(listener -> listener.onPunishmentCreated(record));
        }
    }

    public void punishmentRevoked(PunishmentRecord record) {
        if (record != null) {
            dispatch(listener -> listener.onPunishmentRevoked(record));
        }
    }

    public void restartScheduled(long restartAtEpochMs) {
        dispatch(listener -> listener.onRestartScheduled(restartAtEpochMs));
    }

    public void restartCancelled() {
        dispatch(Listener::onRestartCancelled);
    }

    public void restartCountdown(long secondsRemaining) {
        dispatch(listener -> listener.onRestartCountdown(secondsRemaining));
    }

    public void restartImminent() {
        dispatch(Listener::onRestartImminent);
    }

    private void dispatch(Consumer<Listener> action) {
        for (Listener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }
}
