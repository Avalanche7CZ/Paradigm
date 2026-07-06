CREATE TABLE IF NOT EXISTS server_instances (
  server_id VARCHAR(64) PRIMARY KEY,
  network_id VARCHAR(64) NOT NULL,
  server_name VARCHAR(128),
  created_at_ms BIGINT,
  last_seen_ms BIGINT
);

CREATE TABLE IF NOT EXISTS players (
  uuid VARCHAR(36) PRIMARY KEY,
  name VARCHAR(64),
  first_seen_ms BIGINT,
  last_seen_ms BIGINT
);

CREATE TABLE IF NOT EXISTS player_homes (
  server_id VARCHAR(64) NOT NULL,
  uuid VARCHAR(36) NOT NULL,
  home_name VARCHAR(64) NOT NULL,
  world_id VARCHAR(128) NOT NULL,
  x DOUBLE NOT NULL,
  y DOUBLE NOT NULL,
  z DOUBLE NOT NULL,
  yaw FLOAT NOT NULL,
  pitch FLOAT NOT NULL,
  created_at_ms BIGINT,
  updated_at_ms BIGINT,
  PRIMARY KEY (server_id, uuid, home_name)
);

CREATE TABLE IF NOT EXISTS player_back_locations (
  server_id VARCHAR(64) NOT NULL,
  uuid VARCHAR(36) NOT NULL,
  world_id VARCHAR(128) NOT NULL,
  x DOUBLE NOT NULL,
  y DOUBLE NOT NULL,
  z DOUBLE NOT NULL,
  yaw FLOAT NOT NULL,
  pitch FLOAT NOT NULL,
  updated_at_ms BIGINT,
  PRIMARY KEY (server_id, uuid)
);

CREATE TABLE IF NOT EXISTS player_ignored_players (
  server_id VARCHAR(64) NOT NULL,
  uuid VARCHAR(36) NOT NULL,
  ignored_uuid VARCHAR(36) NOT NULL,
  PRIMARY KEY (server_id, uuid, ignored_uuid)
);

CREATE TABLE IF NOT EXISTS warps (
  server_id VARCHAR(64) NOT NULL,
  name VARCHAR(64) NOT NULL,
  world_id VARCHAR(128) NOT NULL,
  x DOUBLE NOT NULL,
  y DOUBLE NOT NULL,
  z DOUBLE NOT NULL,
  yaw FLOAT NOT NULL,
  pitch FLOAT NOT NULL,
  permission VARCHAR(255),
  description TEXT,
  created_by VARCHAR(64),
  created_at_ms BIGINT,
  updated_at_ms BIGINT,
  PRIMARY KEY (server_id, name)
);

CREATE TABLE IF NOT EXISTS admin_state (
  server_id VARCHAR(64) NOT NULL,
  state_key VARCHAR(128) NOT NULL,
  state_value TEXT,
  updated_at_ms BIGINT,
  PRIMARY KEY (server_id, state_key)
);

CREATE INDEX idx_player_homes_uuid ON player_homes(uuid);
