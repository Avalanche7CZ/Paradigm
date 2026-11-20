CREATE TABLE IF NOT EXISTS discord_link_networks (
    network_id VARCHAR(64) NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS discord_account_links (
    network_id VARCHAR(64) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    discord_id VARCHAR(25) NOT NULL,
    PRIMARY KEY (network_id, player_uuid),
    UNIQUE (network_id, discord_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS discord_link_codes (
    network_id VARCHAR(64) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    digest CHAR(64) NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    PRIMARY KEY (network_id, player_uuid),
    UNIQUE (network_id, digest),
    INDEX (network_id, expires_at_ms)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS discord_link_claim_limits (
    network_id VARCHAR(64) NOT NULL,
    discord_id VARCHAR(25) NOT NULL,
    attempts INTEGER NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    PRIMARY KEY (network_id, discord_id),
    INDEX (network_id, expires_at_ms)
) ENGINE=InnoDB;
