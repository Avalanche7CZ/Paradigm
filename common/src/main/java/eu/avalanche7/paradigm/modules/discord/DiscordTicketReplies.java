package eu.avalanche7.paradigm.modules.discord;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.modules.discord.client.DiscordInboundMessage;
import eu.avalanche7.paradigm.modules.discord.client.DiscordRestClient;
import eu.avalanche7.paradigm.modules.tickets.Ticket;
import eu.avalanche7.paradigm.modules.tickets.TicketActor;
import eu.avalanche7.paradigm.modules.tickets.TicketError;
import eu.avalanche7.paradigm.modules.tickets.TicketService;

final class DiscordTicketReplies {
    private static final long RETRY_DELAY_MS = 60_000L;
    private static final int DISCORD_TEXT_LIMIT = 2_000;

    private final DiscordTicketRoomStore store;
    private final DiscordRestClient rest;
    private final Consumer<String> warning;

    DiscordTicketReplies(DiscordTicketRoomStore store, DiscordRestClient rest, Consumer<String> warning) {
        this.store = store;
        this.rest = rest;
        this.warning = warning;
    }

    void deliver(DiscordService.LinkingSettings linking, String roles, BooleanSupplier current) {
        if (!linking.owner() || !current.getAsBoolean()) {
            return;
        }
        String guild = linking.guild();
        store.unconfirmedReply(guild).ifPresent(id -> warning.accept(
                "Ticket reply " + id + " has an unconfirmed Discord delivery. It will not be retried automatically."));
        for (var reply : store.repliesToDeliver(guild, roles, System.currentTimeMillis())) {
            String name = reply.author() == null || reply.author().isBlank() ? "Minecraft player" : reply.author();
            String prefix = name + (reply.offset() == 0 ? ": " : " (continued): ");
            int end = Math.min(reply.text().length(), reply.offset() + DISCORD_TEXT_LIMIT - prefix.length());
            if (end < reply.text().length() && Character.isHighSurrogate(reply.text().charAt(end - 1))) {
                end--;
            }
            if (!current.getAsBoolean()) {
                return;
            }
            if (!store.claimReply(guild, roles, reply)) {
                continue;
            }
            if (!current.getAsBoolean()) {
                store.finishReply(reply, "READY", reply.offset(), 0);
                return;
            }
            var payload = textPayload(prefix + reply.text().substring(reply.offset(), end));
            var result = rest.postMessage(reply.channel(), payload.toString());
            if (result.success()) {
                store.finishReply(reply, end == reply.text().length() ? "SENT" : "READY", end, 0);
            } else if (result.status() >= 400 && result.status() < 500 && result.status() != 408) {
                store.finishReply(reply, "READY", reply.offset(), System.currentTimeMillis() + RETRY_DELAY_MS);
                warning.accept("Discord rejected ticket reply " + reply.id() + " (HTTP " + result.status()
                        + "). Delivery will be retried.");
            } else {
                warning.accept("Ticket reply " + reply.id()
                        + " has an unconfirmed Discord delivery. It will not be retried automatically.");
            }
        }
    }

    boolean handle(DiscordService.LinkingSettings linking, DiscordInboundMessage message, DiscordAccountLinks links,
                   TicketService tickets, BiFunction<UUID, Ticket, TicketActor> actors,
                   boolean contentAvailable, BooleanSupplier current) {
        if (!linking.guild().equals(message.guildId())) {
            return false;
        }
        String ticketId = store.boundTicket(message.guildId(), message.channelId()).orElse(null);
        if (ticketId == null) {
            return false;
        }
        if (!linking.owner() || !current.getAsBoolean() || message.bot() || message.webhook() || message.system()
                || !DiscordConfigHandler.isGuildId(message.messageId()) || !DiscordConfigHandler.isGuildId(message.authorId())) {
            return true;
        }
        if (!message.hasContent() && (contentAvailable || message.hasRichContent())) {
            return true;
        }

        try {
            if (store.handledReply(ticketId, message.messageId())) {
                return true;
            }
            if (!message.hasContent()) {
                reject(ticketId, message, "The bot cannot read message text. Ask an administrator to enable Message Content Intent.",
                        current);
                return true;
            }
            UUID uuid = links.minecraftUuid(message.authorId()).orElse(null);
            if (uuid == null) {
                reject(ticketId, message, "Link your Minecraft account before replying.", current);
                return true;
            }
            Ticket ticket = store.ticket(ticketId).orElse(null);
            if (ticket == null) {
                reject(ticketId, message, "The bound ticket could not be loaded.", current);
                return true;
            }
            TicketActor actor = actors.apply(uuid, ticket);
            if (!current.getAsBoolean()) {
                return true;
            }
            var outcome = tickets.replyFromDiscord(actor, ticket, message.content(), message.authorId(), message.messageId());
            if (outcome.error() == TicketError.STALE_TICKET && current.getAsBoolean()) {
                outcome = tickets.replyFromDiscord(actor, ticket, message.content(), message.authorId(), message.messageId());
            }
            if (!outcome.ok()) {
                String detail = outcome.error().fallback().replace("{ticket}", ticket.ticketKey());
                reject(ticketId, message, detail.replace("{limit}", outcome.details().getOrDefault("limit", "the configured limit")),
                        current);
            }
        } catch (RuntimeException failure) {
            warning.accept("Could not confirm Discord reply " + message.messageId() + " (" + failure.getClass().getSimpleName() + ").");
            if (!handledAfterFailure(ticketId, message.messageId())) {
                notice(message, "Paradigm could not confirm this reply. Check the ticket before sending it again.", current);
            }
        }
        return true;
    }

    private boolean handledAfterFailure(String ticketId, String messageId) {
        try {
            return store.handledReply(ticketId, messageId);
        } catch (RuntimeException failure) {
            warning.accept("Could not look up the stored result of Discord reply " + messageId + ".");
            return false;
        }
    }

    private void reject(String ticketId, DiscordInboundMessage message, String detail, BooleanSupplier current) {
        if (current.getAsBoolean() && store.rejectReply(ticketId, message.messageId())) {
            notice(message, "Reply not saved. " + detail, current);
        }
    }

    private void notice(DiscordInboundMessage message, String detail, BooleanSupplier current) {
        if (!current.getAsBoolean()) {
            return;
        }
        JsonObject payload = textPayload(detail);
        JsonObject reference = new JsonObject();
        reference.addProperty("message_id", message.messageId());
        reference.addProperty("fail_if_not_exists", false);
        payload.add("message_reference", reference);
        var result = rest.postMessage(message.channelId(), payload.toString());
        if (!result.success()) {
            warning.accept("Could not send a reply status to Discord for message " + message.messageId()
                    + " (HTTP " + result.status() + ").");
        }
    }

    private static JsonObject textPayload(String content) {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", content);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        mentions.addProperty("replied_user", false);
        payload.add("allowed_mentions", mentions);
        return payload;
    }
}
