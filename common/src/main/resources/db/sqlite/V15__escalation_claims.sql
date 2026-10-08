CREATE TABLE IF NOT EXISTS moderation_escalation_claims (
  network_id VARCHAR(64) NOT NULL,
  subject_uuid VARCHAR(64) NOT NULL,
  rule_key VARCHAR(64) NOT NULL,
  consumed_at_ms BIGINT NOT NULL,
  punishment_id VARCHAR(64) NULL,
  PRIMARY KEY (network_id, subject_uuid, rule_key)
);
