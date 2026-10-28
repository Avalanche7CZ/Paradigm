ALTER TABLE players ADD COLUMN nickname VARCHAR(16);
ALTER TABLE players ADD COLUMN nickname_key VARCHAR(16);
CREATE UNIQUE INDEX players_nickname_key ON players(nickname_key);
