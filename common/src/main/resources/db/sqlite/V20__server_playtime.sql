ALTER TABLE player_playtime_streams RENAME TO player_playtime_legacy_streams;
ALTER TABLE players ADD COLUMN unattributed_playtime_ms BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS player_server_playtime (
    uuid VARCHAR(36) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    playtime_ms BIGINT NOT NULL,
    first_seen_ms BIGINT NOT NULL,
    last_seen_ms BIGINT NOT NULL,
    PRIMARY KEY (uuid, server_id)
);
CREATE TABLE IF NOT EXISTS player_server_playtime_streams (
    uuid VARCHAR(36) NOT NULL,
    writer_id VARCHAR(36) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    cumulative_ms BIGINT NOT NULL,
    PRIMARY KEY (uuid, writer_id)
);
CREATE INDEX player_server_playtime_ranking ON player_server_playtime(server_id, playtime_ms);
UPDATE players SET unattributed_playtime_ms = playtime_ms;
