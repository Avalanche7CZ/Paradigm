ALTER TABLE players ADD COLUMN playtime_ms BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_players_playtime ON players(playtime_ms);
