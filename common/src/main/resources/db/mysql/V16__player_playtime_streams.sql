CREATE TABLE IF NOT EXISTS player_playtime_streams (
    uuid VARCHAR(36) NOT NULL,
    writer_id VARCHAR(36) NOT NULL,
    cumulative_ms BIGINT NOT NULL,
    PRIMARY KEY (uuid, writer_id)
) ENGINE=InnoDB;
