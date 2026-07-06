CREATE TABLE IF NOT EXISTS moderation_punishments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  type VARCHAR(32) NOT NULL,
  scope VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
  server_id VARCHAR(64) NULL,
  uuid VARCHAR(36),
  name VARCHAR(64),
  reason TEXT,
  actor VARCHAR(64),
  created_at_ms BIGINT NOT NULL,
  expires_at_ms BIGINT,
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS moderation_warnings (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  uuid VARCHAR(36),
  name VARCHAR(64),
  reason TEXT,
  actor VARCHAR(64),
  created_at_ms BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS moderation_jails (
  server_id VARCHAR(64) NOT NULL,
  uuid VARCHAR(36) NOT NULL,
  name VARCHAR(64),
  reason TEXT,
  actor VARCHAR(64),
  world_id VARCHAR(128),
  x DOUBLE,
  y DOUBLE,
  z DOUBLE,
  yaw FLOAT,
  pitch FLOAT,
  created_at_ms BIGINT,
  expires_at_ms BIGINT,
  PRIMARY KEY (server_id, uuid)
);

CREATE INDEX idx_moderation_punishments_uuid ON moderation_punishments(uuid);
CREATE INDEX idx_moderation_punishments_active ON moderation_punishments(active);
CREATE INDEX idx_moderation_punishments_expires ON moderation_punishments(expires_at_ms);
