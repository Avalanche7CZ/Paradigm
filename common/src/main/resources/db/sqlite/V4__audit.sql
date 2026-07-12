CREATE TABLE IF NOT EXISTS dashboard_audit_entries (
  id TEXT PRIMARY KEY,
  timestamp_ms INTEGER NOT NULL,
  network_id TEXT NOT NULL,
  server_id TEXT NOT NULL,
  actor_uuid TEXT,
  actor_name TEXT,
  source TEXT NOT NULL,
  action_type TEXT NOT NULL,
  target_uuid TEXT,
  target_name TEXT,
  result TEXT NOT NULL,
  message TEXT,
  details_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_dashboard_audit_timestamp ON dashboard_audit_entries(timestamp_ms);
CREATE INDEX IF NOT EXISTS idx_dashboard_audit_actor_uuid ON dashboard_audit_entries(actor_uuid);
CREATE INDEX IF NOT EXISTS idx_dashboard_audit_actor_name ON dashboard_audit_entries(actor_name);
CREATE INDEX IF NOT EXISTS idx_dashboard_audit_action_type ON dashboard_audit_entries(action_type);
