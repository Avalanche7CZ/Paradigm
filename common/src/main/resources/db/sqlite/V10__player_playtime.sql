ALTER TABLE players ADD COLUMN playtime_ms INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_players_playtime ON players(playtime_ms);
