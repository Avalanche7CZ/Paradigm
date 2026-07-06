CREATE TABLE IF NOT EXISTS server_instances (
  server_id TEXT PRIMARY KEY,
  network_id TEXT NOT NULL,
  server_name TEXT,
  created_at_ms INTEGER,
  last_seen_ms INTEGER
);

CREATE TABLE IF NOT EXISTS players (
  uuid TEXT PRIMARY KEY,
  name TEXT,
  first_seen_ms INTEGER,
  last_seen_ms INTEGER
);

CREATE TABLE IF NOT EXISTS player_homes (
  server_id TEXT NOT NULL,
  uuid TEXT NOT NULL,
  home_name TEXT NOT NULL,
  world_id TEXT NOT NULL,
  x REAL NOT NULL,
  y REAL NOT NULL,
  z REAL NOT NULL,
  yaw REAL NOT NULL,
  pitch REAL NOT NULL,
  created_at_ms INTEGER,
  updated_at_ms INTEGER,
  PRIMARY KEY (server_id, uuid, home_name)
);

CREATE TABLE IF NOT EXISTS player_back_locations (
  server_id TEXT NOT NULL,
  uuid TEXT NOT NULL,
  world_id TEXT NOT NULL,
  x REAL NOT NULL,
  y REAL NOT NULL,
  z REAL NOT NULL,
  yaw REAL NOT NULL,
  pitch REAL NOT NULL,
  updated_at_ms INTEGER,
  PRIMARY KEY (server_id, uuid)
);

CREATE TABLE IF NOT EXISTS player_ignored_players (
  server_id TEXT NOT NULL,
  uuid TEXT NOT NULL,
  ignored_uuid TEXT NOT NULL,
  PRIMARY KEY (server_id, uuid, ignored_uuid)
);

CREATE TABLE IF NOT EXISTS warps (
  server_id TEXT NOT NULL,
  name TEXT NOT NULL,
  world_id TEXT NOT NULL,
  x REAL NOT NULL,
  y REAL NOT NULL,
  z REAL NOT NULL,
  yaw REAL NOT NULL,
  pitch REAL NOT NULL,
  permission TEXT,
  description TEXT,
  created_by TEXT,
  created_at_ms INTEGER,
  updated_at_ms INTEGER,
  PRIMARY KEY (server_id, name)
);

CREATE TABLE IF NOT EXISTS admin_state (
  server_id TEXT NOT NULL,
  state_key TEXT NOT NULL,
  state_value TEXT,
  updated_at_ms INTEGER,
  PRIMARY KEY (server_id, state_key)
);

CREATE INDEX IF NOT EXISTS idx_player_homes_uuid ON player_homes(uuid);
