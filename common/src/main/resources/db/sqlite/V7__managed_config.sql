CREATE TABLE IF NOT EXISTS managed_config (
  id TEXT PRIMARY KEY,
  network_id TEXT NOT NULL,
  scope TEXT NOT NULL,
  server_id TEXT NOT NULL DEFAULT '',
  section TEXT NOT NULL,
  data_json TEXT NOT NULL,
  schema_fingerprint TEXT NOT NULL,
  revision INTEGER NOT NULL DEFAULT 0,
  updated_at_ms INTEGER NOT NULL,
  updated_by_uuid TEXT,
  updated_by_name TEXT,
  source_server_id TEXT,
  UNIQUE (network_id, scope, server_id, section)
);

CREATE INDEX IF NOT EXISTS idx_managed_config_server ON managed_config(network_id, server_id);

CREATE TABLE IF NOT EXISTS managed_config_applied (
  id TEXT PRIMARY KEY,
  network_id TEXT NOT NULL,
  server_id TEXT NOT NULL,
  section TEXT NOT NULL,
  applied_global_revision INTEGER NOT NULL DEFAULT 0,
  applied_server_revision INTEGER NOT NULL DEFAULT 0,
  applied_at_ms INTEGER NOT NULL,
  last_error TEXT,
  baseline_json TEXT,
  UNIQUE (network_id, server_id, section)
);

CREATE INDEX IF NOT EXISTS idx_managed_config_applied_server ON managed_config_applied(network_id, server_id);

CREATE TABLE IF NOT EXISTS managed_config_adoption_request (
  network_id TEXT NOT NULL,
  server_id TEXT NOT NULL,
  section TEXT NOT NULL,
  requested_at_ms INTEGER NOT NULL,
  requested_by_uuid TEXT,
  requested_by_name TEXT,
  PRIMARY KEY (network_id, server_id, section)
);
