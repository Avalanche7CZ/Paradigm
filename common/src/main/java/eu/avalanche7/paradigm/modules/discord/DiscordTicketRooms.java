package eu.avalanche7.paradigm.modules.discord;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.modules.discord.DiscordTicketRoomStore.Room;
import eu.avalanche7.paradigm.modules.discord.client.DiscordJson;
import eu.avalanche7.paradigm.modules.discord.client.DiscordRestClient;
import eu.avalanche7.paradigm.modules.tickets.Ticket;

final class DiscordTicketRooms {
    private static final String MARKER_PREFIX = "Paradigm ticket room ";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(ticket_key|server|category|player)}");
    private static final long VIEW = 1L << 10;
    private static final long SEND = 1L << 11;
    private static final long HISTORY = 1L << 16;
    private static final long REACT = 1L << 6;
    private static final long THREADS = (1L << 35) | (1L << 36) | (1L << 38);
    private static final long READ_ONLY = SEND | REACT | THREADS;
    private static final long RETRY_DELAY = 30_000L;

    record Settings(String guild, String category, List<String> roles, String template, boolean admits) {
        String rolesKey(String bot) {
            return bot + ":" + String.join(",", roles);
        }
    }

    private final DiscordTicketRoomStore store;
    private final DiscordAccountLinks links;
    private final DiscordRestClient rest;
    private final Function<Ticket, String> originatingServer;
    private final Consumer<String> warning;

    DiscordTicketRooms(DiscordTicketRoomStore store, DiscordAccountLinks links, DiscordRestClient rest,
                       Function<Ticket, String> originatingServer, Consumer<String> warning) {
        this.store = store;
        this.links = links;
        this.rest = rest;
        this.originatingServer = originatingServer;
        this.warning = warning;
    }

    void sweep(Settings settings, String bot, long enabledAt, BooleanSupplier current) {
        if (!current.getAsBoolean()) {
            return;
        }
        if (settings.admits()) {
            store.discover(settings.guild(), enabledAt);
        } else {
            store.pause(settings.guild());
        }
        for (Room room : store.work(settings.guild(), settings.rolesKey(bot), System.currentTimeMillis())) {
            if (!current.getAsBoolean()) {
                return;
            }
            try {
                switch (room.phase()) {
                    case "PENDING" -> provision(room, settings, bot, current);
                    case "CREATING" -> reconcile(room, settings, bot, current);
                    case "BOUND" -> synchronize(room, settings, bot, current);
                    default -> {}
                }
            } catch (DiscordRestClient.DiscordApiException failure) {
                defer(room, "Discord request failed (HTTP " + failure.status() + ").");
                if (failure.isRetryable() || failure.status() == 0) {
                    return;
                }
            } catch (RuntimeException failure) {
                defer(room, "Storage or channel state could not be confirmed (" + failure.getClass().getSimpleName() + ").");
                return;
            }
        }
    }

    private void provision(Room room, Settings settings, String bot, BooleanSupplier current)
            throws DiscordRestClient.DiscordApiException {
        Ticket ticket = store.ticket(room.ticketId()).orElseThrow();
        String player = link(ticket, true).orElse(null);
        if (player == null) {
            store.changePhase(room.ticketId(), "PENDING", "SKIPPED");
            return;
        }
        String server = originatingServer.apply(ticket);
        JsonObject payload = new JsonObject();
        payload.addProperty("name", name(settings.template(), ticket, server));
        payload.addProperty("type", 0);
        payload.addProperty("parent_id", settings.category());
        payload.addProperty("topic", MARKER_PREFIX + room.marker());
        payload.add("permission_overwrites", overwrites(settings, bot, player, ticket.status().isActive()));

        if (!settings.admits() || !current.getAsBoolean() || !store.beginCreate(room.ticketId(), player)) {
            return;
        }
        if (!current.getAsBoolean()) {
            store.changePhase(room.ticketId(), "CREATING", "PENDING");
            return;
        }
        JsonObject channel;
        try {
            channel = rest.createGuildTextChannel(settings.guild(), payload);
        } catch (DiscordRestClient.DiscordApiException failure) {
            if (definiteRejection(failure)) {
                store.changePhase(room.ticketId(), "CREATING", "PENDING");
            } else {
                warning.accept("Ticket " + room.ticketId() + " channel creation is unconfirmed. Reconciling by its saved marker.");
            }
            throw failure;
        }
        if (!matches(channel, room, settings.guild())) {
            defer(room, "Discord returned an unverified channel. Waiting for marker reconciliation.");
            return;
        }
        bind(room, settings, bot, DiscordJson.string(channel, "id"), player, current);
    }

    private void reconcile(Room room, Settings settings, String bot, BooleanSupplier current)
            throws DiscordRestClient.DiscordApiException {
        String id = null;
        for (JsonElement element : rest.listGuildChannels(settings.guild())) {
            if (element.isJsonObject() && matches(element.getAsJsonObject(), room, settings.guild())) {
                if (id != null) {
                    defer(room, "More than one Discord channel has the recovery marker. An administrator must review it.");
                    return;
                }
                id = DiscordJson.string(element.getAsJsonObject(), "id");
            }
        }
        if (id == null) {
            defer(room, "No channel found for unconfirmed creation. Automatic duplicate creation is disabled.");
            return;
        }
        bind(room, settings, bot, id, room.possibleUser(), current);
    }

    private void bind(Room room, Settings settings, String bot, String channel, String possibleUser, BooleanSupplier current)
            throws DiscordRestClient.DiscordApiException {
        if (store.bind(room.ticketId(), channel)) {
            synchronize(new Room(room.ticketId(), channel, room.marker(), "BOUND", true, possibleUser), settings, bot, current);
        }
    }

    private void synchronize(Room room, Settings settings, String bot, BooleanSupplier current)
            throws DiscordRestClient.DiscordApiException {
        Ticket ticket = store.ticket(room.ticketId()).orElseThrow();
        String player = link(ticket, false).orElse(null);
        boolean writable = ticket.status().isActive();
        if (!current.getAsBoolean()) {
            return;
        }
        if (room.possibleUser() != null && !Objects.equals(room.possibleUser(), player) && !bot.equals(room.possibleUser())) {
            rest.removeChannelOverwrite(room.channelId(), room.possibleUser());
        }
        if (!current.getAsBoolean()) {
            return;
        }
        store.recordPossibleAccess(room.ticketId(), player);
        if (!current.getAsBoolean()) {
            return;
        }
        rest.setChannelOverwrites(room.channelId(), overwrites(settings, bot, player, writable));
        store.applied(room.ticketId(), player, settings.rolesKey(bot), writable);
        if (!room.openingPending()) {
            return;
        }
        JsonObject opening = new JsonObject();
        opening.addProperty("content", "Minecraft ticket " + ticket.ticketKey() + " · Category: " + ticket.category()
                + " · Server: " + originatingServer.apply(ticket)
                + "\nText replies sync with Minecraft. Link your Minecraft account before replying here.");
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        opening.add("allowed_mentions", mentions);
        if (!current.getAsBoolean() || !store.openingPending(room.ticketId(), true, false)) {
            return;
        }
        try {
            rest.postTicketOpening(room.channelId(), opening);
        } catch (DiscordRestClient.DiscordApiException failure) {
            if (definiteRejection(failure)) {
                store.openingPending(room.ticketId(), false, true);
            } else {
                warning.accept("Ticket " + room.ticketId() + " opening message is unconfirmed and will not be posted again automatically.");
            }
            throw failure;
        }
    }

    private Optional<String> link(Ticket ticket, boolean atCreation) {
        UUID player;
        try {
            player = UUID.fromString(ticket.creatorUuid());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
        return atCreation ? links.discordIdAt(player, ticket.createdAtMs()) : links.discordId(player);
    }

    private static boolean matches(JsonObject channel, Room room, String guild) {
        String responseGuild = DiscordJson.string(channel, "guild_id");
        return Integer.valueOf(0).equals(DiscordJson.integer(channel, "type"))
                && (responseGuild == null || guild.equals(responseGuild))
                && DiscordConfigHandler.isGuildId(DiscordJson.string(channel, "id"))
                && (MARKER_PREFIX + room.marker()).equals(DiscordJson.string(channel, "topic"));
    }

    private static boolean definiteRejection(DiscordRestClient.DiscordApiException failure) {
        return switch (failure.status()) {
            case 400, 401, 403, 404, 429 -> true;
            default -> false;
        };
    }

    private void defer(Room room, String detail) {
        store.retryAt(room.ticketId(), System.currentTimeMillis() + RETRY_DELAY);
        warning.accept("Ticket " + room.ticketId() + " in Discord: " + detail);
    }

    static String name(String template, Ticket ticket, String server) {
        String fallback = "ticket-" + ticket.ticketKey().toLowerCase(Locale.ROOT);
        if (template == null) {
            return fallback;
        }
        String rendered = PLACEHOLDER.matcher(template).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                switch (match.group(1)) {
                    case "ticket_key" -> ticket.ticketKey();
                    case "server" -> server;
                    case "category" -> ticket.category();
                    default -> ticket.creatorName() == null ? "" : ticket.creatorName();
                }));
        if (rendered.indexOf('{') >= 0 || rendered.indexOf('}') >= 0) {
            return fallback;
        }
        String normalized = rendered.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return normalized.isEmpty() || normalized.length() > 100 ? fallback : normalized;
    }

    static JsonArray overwrites(Settings settings, String bot, String player, boolean writable) {
        JsonArray result = new JsonArray();
        result.add(overwrite(settings.guild(), 0, 0, VIEW | READ_ONLY));
        result.add(overwrite(bot, 1, VIEW | HISTORY | SEND, 0));
        long allow = VIEW | HISTORY | (writable ? SEND | REACT : 0);
        long deny = writable ? THREADS : READ_ONLY;
        for (String role : settings.roles()) {
            if (settings.guild().equals(role)) {
                throw new IllegalArgumentException("The @everyone role cannot be a ticket staff role.");
            }
            result.add(overwrite(role, 0, allow, deny));
        }
        if (player != null && !bot.equals(player)) {
            result.add(overwrite(player, 1, allow, deny));
        }
        return result;
    }

    private static JsonObject overwrite(String id, int type, long allow, long deny) {
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("type", type);
        result.addProperty("allow", Long.toString(allow));
        result.addProperty("deny", Long.toString(deny));
        return result;
    }
}
