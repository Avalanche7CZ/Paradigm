CREATE TABLE IF NOT EXISTS moderation_punishments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL,
  scope TEXT NOT NULL DEFAULT 'GLOBAL',
  server_id TEXT NULL,
  uuid TEXT,
  name TEXT,
  reason TEXT,
  actor TEXT,
  created_at_ms INTEGER NOT NULL,
  expires_at_ms INTEGER,
  active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS moderation_warnings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid TEXT,
  name TEXT,
  reason TEXT,
  actor TEXT,
  created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS moderation_jails (
  server_id TEXT NOT NULL,
  uuid TEXT NOT NULL,
  name TEXT,
  reason TEXT,
  actor TEXT,
  world_id TEXT,
  x REAL,
  y REAL,
  z REAL,
  yaw REAL,
  pitch REAL,
  created_at_ms INTEGER,
  expires_at_ms INTEGER,
  PRIMARY KEY (server_id, uuid)
);

CREATE INDEX IF NOT EXISTS idx_moderation_punishments_uuid ON moderation_punishments(uuid);
CREATE INDEX IF NOT EXISTS idx_moderation_punishments_active ON moderation_punishments(active);
CREATE INDEX IF NOT EXISTS idx_moderation_punishments_expires ON moderation_punishments(expires_at_ms);
