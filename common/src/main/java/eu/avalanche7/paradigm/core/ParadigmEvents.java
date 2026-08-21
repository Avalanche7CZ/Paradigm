package eu.avalanche7.paradigm.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.tickets.Ticket;
import eu.avalanche7.paradigm.modules.tickets.TicketEvent;

public final class ParadigmEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmEvents.class);

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

        default void onTicketCreated(Ticket ticket, TicketEvent event) {
        }

        default void onTicketReplied(Ticket ticket, TicketEvent event) {
        }

        default void onTicketClaimed(Ticket ticket, TicketEvent event) {
        }

        default void onTicketAssigned(Ticket ticket, TicketEvent event) {
        }

        default void onTicketResolved(Ticket ticket, TicketEvent event) {
        }

        default void onTicketClosed(Ticket ticket, TicketEvent event) {
        }

        default void onTicketReopened(Ticket ticket, TicketEvent event) {
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

    public void ticketCreated(Ticket ticket, TicketEvent event) {
        if (ticket != null) {
            dispatch(listener -> listener.onTicketCreated(ticket, event));
        }
    }

    public void ticketReplied(Ticket ticket, TicketEvent event) {
        if (ticket != null) {
            dispatch(listener -> listener.onTicketReplied(ticket, event));
        }
    }

    public void ticketClaimed(Ticket ticket, TicketEvent event) {
        if (ticket != null) {
            dispatch(listener -> listener.onTicketClaimed(ticket, event));
        }
    }

    public void ticketAssigned(Ticket ticket, TicketEvent event) {
        if (ticket != null) {
            dispatch(listener -> listener.onTicketAssigned(ticket, event));
        }
    }

    public void ticketResolved(Ticket ticket, TicketEvent event) {
        if (ticket != null) {
            dispatch(listener -> listener.onTicketResolved(ticket, event));
        }
    }

    public void ticketClosed(Ticket ticket, TicketEvent event) {
        if (ticket != null) {
            dispatch(listener -> listener.onTicketClosed(ticket, event));
        }
    }

    public void ticketReopened(Ticket ticket, TicketEvent event) {
        if (ticket != null) {
            dispatch(listener -> listener.onTicketReopened(ticket, event));
        }
    }

    private void dispatch(Consumer<Listener> action) {
        for (Listener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("Paradigm event listener {} failed: {}", listener.getClass().getName(), failure.toString(), failure);
            }
        }
    }
}
