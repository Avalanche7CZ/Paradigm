package eu.avalanche7.paradigm.modules.discord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.modules.discord.client.DiscordJson;
import eu.avalanche7.paradigm.modules.discord.client.DiscordRestClient;
import eu.avalanche7.paradigm.modules.discord.client.DiscordRestClient.DiscordApiException;
import eu.avalanche7.paradigm.utils.AtomicFileIO;

final class DiscordLinkRegistration {
    private static final String COMMAND = DiscordService.LINK_COMMAND_NAME;
    private static final Gson GSON = new Gson();
    private final Path receiptPath;

    DiscordLinkRegistration(Path receiptPath) {
        this.receiptPath = receiptPath;
    }

    record Owner(String network, String server) {}
    private record Receipt(Owner owner, String guild, String application, String id, String version) {}

    boolean register(DiscordRestClient client, Owner owner, String guild, String application, BooleanSupplier stillOwner)
            throws IOException, DiscordApiException {
        Receipt receipt = read();
        if (receipt != null && (!receipt.owner().equals(owner) || !receipt.guild().equals(guild)
                || !receipt.application().equals(application))) {
            throw new IOException("The saved /link registration belongs to another server, guild, or application.");
        }

        for (JsonElement element : client.listGuildCommands(guild, application)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject command = element.getAsJsonObject();
            if (matches(receipt, command)) {
                return true;
            }
            if (isLink(command) || (receipt != null && receipt.id().equals(DiscordJson.string(command, "id")))) {
                throw new IOException("The guild has a /link registration this instance cannot verify as its own.");
            }
        }
        if (!stillOwner.getAsBoolean()) {
            return false;
        }
        JsonObject command = new JsonObject();
        command.addProperty("name", COMMAND);
        command.addProperty("description", "Link your Minecraft account");
        JsonObject created = client.createNewGuildCommand(guild, application, command.toString());
        Receipt createdReceipt = new Receipt(owner, guild, application, DiscordJson.string(created, "id"),
                DiscordJson.string(created, "version"));
        if (!valid(createdReceipt) || !matches(createdReceipt, created)) {
            throw new IOException("Discord did not return a verifiable /link registration.");
        }
        AtomicFileIO.writeUtf8Atomic(receiptPath, writer -> GSON.toJson(createdReceipt, writer));
        return true;
    }

    boolean remove(DiscordRestClient client, Owner owner, BooleanSupplier stillOwner) throws IOException, DiscordApiException {
        Receipt receipt = read();
        if (receipt == null) {
            return false;
        }
        if (!receipt.owner().equals(owner)) {
            throw new IOException("The saved /link registration belongs to another server.");
        }
        boolean found = false;
        for (JsonElement element : client.listGuildCommands(receipt.guild(), receipt.application())) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject command = element.getAsJsonObject();
            if (matches(receipt, command)) {
                found = true;
            } else if (isLink(command) || receipt.id().equals(DiscordJson.string(command, "id"))) {
                throw new IOException("The previous /link registration was changed or replaced.");
            }
        }
        if (!stillOwner.getAsBoolean()) {
            return false;
        }
        if (found) {
            client.deleteGuildCommand(receipt.guild(), receipt.application(), receipt.id());
        }
        Files.deleteIfExists(receiptPath);
        return true;
    }

    private Receipt read() throws IOException {
        try (var reader = Files.newBufferedReader(receiptPath)) {
            Receipt receipt = GSON.fromJson(reader, Receipt.class);
            if (!valid(receipt)) {
                throw new IOException("The saved /link registration receipt is invalid.");
            }
            return receipt;
        } catch (NoSuchFileException missing) {
            return null;
        } catch (com.google.gson.JsonParseException malformed) {
            throw new IOException("The saved /link registration receipt is invalid.", malformed);
        }
    }

    private static boolean valid(Receipt receipt) {
        return receipt != null && receipt.owner() != null && receipt.owner().network() != null && receipt.owner().server() != null
                && DiscordConfigHandler.isGuildId(receipt.guild()) && DiscordConfigHandler.isGuildId(receipt.application())
                && DiscordConfigHandler.isGuildId(receipt.id()) && DiscordConfigHandler.isGuildId(receipt.version());
    }

    private static boolean isLink(JsonObject command) {
        return COMMAND.equals(DiscordJson.string(command, "name"))
                && Integer.valueOf(1).equals(DiscordJson.integer(command, "type"));
    }

    private static boolean matches(Receipt receipt, JsonObject command) {
        return receipt != null && isLink(command)
                && receipt.id().equals(DiscordJson.string(command, "id"))
                && receipt.version().equals(DiscordJson.string(command, "version"))
                && receipt.application().equals(DiscordJson.string(command, "application_id"))
                && receipt.guild().equals(DiscordJson.string(command, "guild_id"));
    }
}
