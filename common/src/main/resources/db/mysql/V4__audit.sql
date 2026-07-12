CREATE TABLE IF NOT EXISTS dashboard_audit_entries (
  id VARCHAR(36) PRIMARY KEY,
  timestamp_ms BIGINT NOT NULL,
  network_id VARCHAR(64) NOT NULL,
  server_id VARCHAR(64) NOT NULL,
  actor_uuid VARCHAR(64),
  actor_name VARCHAR(64),
  source VARCHAR(32) NOT NULL,
  action_type VARCHAR(64) NOT NULL,
  target_uuid VARCHAR(64),
  target_name VARCHAR(64),
  result VARCHAR(32) NOT NULL,
  message VARCHAR(255),
  details_json TEXT
);

CREATE INDEX idx_dashboard_audit_timestamp ON dashboard_audit_entries(timestamp_ms);
CREATE INDEX idx_dashboard_audit_actor_uuid ON dashboard_audit_entries(actor_uuid);
CREATE INDEX idx_dashboard_audit_actor_name ON dashboard_audit_entries(actor_name);
CREATE INDEX idx_dashboard_audit_action_type ON dashboard_audit_entries(action_type);
