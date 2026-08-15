CREATE TABLE IF NOT EXISTS managed_config (
  id VARCHAR(255) PRIMARY KEY,
  network_id VARCHAR(64) NOT NULL,
  scope VARCHAR(16) NOT NULL,
  server_id VARCHAR(64) NOT NULL DEFAULT '',
  section VARCHAR(64) NOT NULL,
  data_json TEXT NOT NULL,
  schema_fingerprint VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  updated_at_ms BIGINT NOT NULL,
  updated_by_uuid VARCHAR(64),
  updated_by_name VARCHAR(64),
  source_server_id VARCHAR(64),
  UNIQUE KEY uq_managed_config_scope (network_id, scope, server_id, section)
);

CREATE INDEX idx_managed_config_server ON managed_config(network_id, server_id);

CREATE TABLE IF NOT EXISTS managed_config_applied (
  id VARCHAR(255) PRIMARY KEY,
  network_id VARCHAR(64) NOT NULL,
  server_id VARCHAR(64) NOT NULL,
  section VARCHAR(64) NOT NULL,
  applied_global_revision BIGINT NOT NULL DEFAULT 0,
  applied_server_revision BIGINT NOT NULL DEFAULT 0,
  applied_at_ms BIGINT NOT NULL,
  last_error VARCHAR(500),
  baseline_json TEXT,
  UNIQUE KEY uq_managed_config_applied_scope (network_id, server_id, section)
);

CREATE INDEX idx_managed_config_applied_server ON managed_config_applied(network_id, server_id);

CREATE TABLE IF NOT EXISTS managed_config_adoption_request (
  network_id VARCHAR(64) NOT NULL,
  server_id VARCHAR(64) NOT NULL,
  section VARCHAR(64) NOT NULL,
  requested_at_ms BIGINT NOT NULL,
  requested_by_uuid VARCHAR(64),
  requested_by_name VARCHAR(64),
  PRIMARY KEY (network_id, server_id, section)
);
