package eu.avalanche7.paradigm.modules.discord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.storage.sql.SqlExecutor;

/** Network-scoped account links. Minecraft and Discord callers supply their authenticated identities. */
public final class DiscordAccountLinks {
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final int CODE_LENGTH = 12;
    private static final long CODE_LIFETIME = Duration.ofMinutes(5).toMillis();
    private static final long CLAIM_WINDOW = Duration.ofMinutes(1).toMillis();
    private static final int CLAIM_LIMIT = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SqlExecutor sql;
    private final String network;
    private final Clock clock;

    public DiscordAccountLinks(SqlExecutor sql, String network) {
        this(sql, network, null);
    }

    DiscordAccountLinks(SqlExecutor sql, String network, Clock clock) {
        this.sql = sql;
        this.network = network;
        this.clock = clock;
    }

    public Optional<String> discordId(UUID player) {
        return lookup("SELECT discord_id FROM discord_account_links WHERE network_id = ? AND player_uuid = ?", player.toString());
    }

    Optional<String> discordIdAt(UUID player, long timestamp) {
        return sql.query("SELECT discord_id FROM discord_account_links WHERE network_id = ? AND player_uuid = ? AND linked_at_ms <= ?", stmt -> {
            stmt.setString(1, network);
            stmt.setString(2, player.toString());
            stmt.setLong(3, timestamp);
        }, rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty());
    }

    public Optional<UUID> minecraftUuid(String discordId) {
        return lookup("SELECT player_uuid FROM discord_account_links WHERE network_id = ? AND discord_id = ?", discordId)
                .map(UUID::fromString);
    }

    /** Returns an empty result if the player already has a link. */
    Optional<String> issue(UUID player, String guild) {
        return mutate(() -> {
            if (discordId(player).isPresent()) {
                return Optional.empty();
            }
            long now = now();
            expire("DELETE FROM discord_link_codes WHERE network_id = ? AND expires_at_ms <= ?", now);
            String code;
            String digest;
            do {
                StringBuilder generated = new StringBuilder(CODE_LENGTH);
                for (int index = 0; index < CODE_LENGTH; index++) {
                    generated.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
                }
                code = generated.toString();
                digest = digest(guild, code);
            } while (lookup("SELECT player_uuid FROM discord_link_codes WHERE network_id = ? AND digest = ?", digest).isPresent());

            deleteForPlayer("discord_link_codes", player);
            String storedDigest = digest;
            sql.update("INSERT INTO discord_link_codes (network_id, player_uuid, digest, expires_at_ms) VALUES (?, ?, ?, ?)", stmt -> {
                stmt.setString(1, network);
                stmt.setString(2, player.toString());
                stmt.setString(3, storedDigest);
                stmt.setLong(4, now + CODE_LIFETIME);
            });
            return Optional.of(code);
        });
    }

    enum Claim {
        LINKED, INVALID_CODE, ALREADY_LINKED, RATE_LIMITED
    }

    Claim claim(String discordId, String guild, String input) {
        return mutate(() -> {
            long now = now();
            if (!allowClaim(discordId, now)) {
                return Claim.RATE_LIMITED;
            }
            if (minecraftUuid(discordId).isPresent()) {
                return Claim.ALREADY_LINKED;
            }
            String code = input == null ? "" : input.trim().toUpperCase(Locale.ROOT);
            if (code.length() != CODE_LENGTH) {
                return Claim.INVALID_CODE;
            }
            expire("DELETE FROM discord_link_codes WHERE network_id = ? AND expires_at_ms <= ?", now);
            Optional<String> player = lookup("SELECT player_uuid FROM discord_link_codes WHERE network_id = ? AND digest = ?",
                    digest(guild, code));
            if (player.isEmpty()) {
                return Claim.INVALID_CODE;
            }
            UUID uuid = UUID.fromString(player.get());
            if (discordId(uuid).isPresent()) {
                return Claim.ALREADY_LINKED;
            }
            sql.update("INSERT INTO discord_account_links (network_id, player_uuid, discord_id, linked_at_ms) VALUES (?, ?, ?, ?)", stmt -> {
                stmt.setString(1, network);
                stmt.setString(2, player.get());
                stmt.setString(3, discordId);
                stmt.setLong(4, now);
            });
            deleteForPlayer("discord_link_codes", uuid);
            return Claim.LINKED;
        });
    }

    boolean unlink(UUID player) {
        return mutate(() -> {
            deleteForPlayer("discord_link_codes", player);
            return deleteForPlayer("discord_account_links", player) != 0;
        });
    }

    private boolean allowClaim(String discordId, long now) {
        expire("DELETE FROM discord_link_claim_limits WHERE network_id = ? AND expires_at_ms <= ?", now);
        int count = sql.query("SELECT attempts FROM discord_link_claim_limits WHERE network_id = ? AND discord_id = ?", stmt -> {
            stmt.setString(1, network);
            stmt.setString(2, discordId);
        }, rs -> rs.next() ? rs.getInt(1) : 0);
        if (count >= CLAIM_LIMIT) {
            return false;
        }
        if (count == 0) {
            sql.update("INSERT INTO discord_link_claim_limits (network_id, discord_id, attempts, expires_at_ms) VALUES (?, ?, 1, ?)", stmt -> {
                stmt.setString(1, network);
                stmt.setString(2, discordId);
                stmt.setLong(3, now + CLAIM_WINDOW);
            });
        } else {
            sql.update("UPDATE discord_link_claim_limits SET attempts = attempts + 1 WHERE network_id = ? AND discord_id = ?", stmt -> {
                stmt.setString(1, network);
                stmt.setString(2, discordId);
            });
        }
        return true;
    }

    private long now() {
        if (clock != null) {
            return clock.millis();
        }
        // Use the shared database clock so issuer and owner need no local clock agreement.
        String query = isSqlite()
                ? "SELECT CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
                : "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)";
        return sql.query(query, null, rs -> {
            rs.next();
            return rs.getLong(1);
        });
    }

    private boolean isSqlite() {
        return "sqlite".equalsIgnoreCase(sql.dialectName());
    }

    private <T> T mutate(Supplier<T> operation) {
        var isolation = isSqlite() ? null : java.sql.Connection.TRANSACTION_READ_COMMITTED;
        return sql.transaction(isolation, () -> {
            // The first write takes the database lock even when the network has no existing rows.
            // MariaDB retains the exclusive row lock on duplicate-key UPDATE until commit.
            String lock = "INSERT INTO discord_link_networks (network_id) VALUES (?)";
            lock += isSqlite()
                    ? " ON CONFLICT(network_id) DO UPDATE SET network_id = excluded.network_id"
                    : " ON DUPLICATE KEY UPDATE network_id = VALUES(network_id)";
            sql.update(lock, stmt -> stmt.setString(1, network));
            return operation.get();
        });
    }

    private Optional<String> lookup(String query, String value) {
        return sql.query(query, stmt -> {
            stmt.setString(1, network);
            stmt.setString(2, value);
        }, rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty());
    }

    private void expire(String query, long now) {
        sql.update(query, stmt -> {
            stmt.setString(1, network);
            stmt.setLong(2, now);
        });
    }

    private int deleteForPlayer(String table, UUID player) {
        return sql.update("DELETE FROM " + table + " WHERE network_id = ? AND player_uuid = ?", stmt -> {
            stmt.setString(1, network);
            stmt.setString(2, player.toString());
        });
    }

    private static String digest(String guild, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((guild + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
