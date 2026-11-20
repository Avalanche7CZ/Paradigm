ALTER TABLE discord_account_links ADD COLUMN linked_at_ms BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS discord_ticket_room_activation (
    network_id VARCHAR(64) NOT NULL,
    guild_id VARCHAR(25) NOT NULL,
    since_ms BIGINT NOT NULL,
    PRIMARY KEY (network_id, guild_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS discord_ticket_rooms (
    network_id VARCHAR(64) NOT NULL,
    ticket_id VARCHAR(64) NOT NULL,
    guild_id VARCHAR(25) NOT NULL,
    channel_id VARCHAR(25),
    marker VARCHAR(64) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    applied_user VARCHAR(25),
    applied_roles VARCHAR(1024),
    applied_writable INTEGER,
    opening_pending INTEGER NOT NULL DEFAULT 1,
    retry_at_ms BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (network_id, ticket_id),
    UNIQUE (guild_id, channel_id),
    UNIQUE (marker)
) ENGINE=InnoDB;

CREATE INDEX idx_tickets_discord_discovery ON tickets (network_id, created_at_ms, ticket_id);
CREATE INDEX idx_discord_ticket_rooms_work ON discord_ticket_rooms (network_id, guild_id, retry_at_ms);
