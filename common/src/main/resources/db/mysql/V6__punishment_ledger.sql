CREATE TABLE IF NOT EXISTS moderation_punishment_ledger (
  punishment_id VARCHAR(40) PRIMARY KEY,
  punishment_type VARCHAR(24) NOT NULL,
  scope VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
  network_id VARCHAR(64),
  server_id VARCHAR(64),
  subject_uuid VARCHAR(36),
  subject_name VARCHAR(64),
  subject_ip_hash CHAR(64),
  subject_ip_address VARCHAR(64),
  reason TEXT,
  actor_uuid VARCHAR(36),
  actor_name VARCHAR(64),
  created_at_ms BIGINT NOT NULL,
  starts_at_ms BIGINT NOT NULL,
  expires_at_ms BIGINT,
  revoked_at_ms BIGINT,
  revoked_by_uuid VARCHAR(36),
  revoked_by_name VARCHAR(64),
  revoke_reason TEXT,
  updated_at_ms BIGINT NOT NULL,
  metadata_json TEXT NOT NULL,
  UNIQUE KEY idx_punishment_ledger_id (punishment_id),
  KEY idx_punishment_ledger_subject (subject_uuid, punishment_type, revoked_at_ms),
  KEY idx_punishment_ledger_ip (subject_ip_hash, punishment_type, revoked_at_ms),
  KEY idx_punishment_ledger_network (network_id),
  KEY idx_punishment_ledger_server (server_id),
  KEY idx_punishment_ledger_created (created_at_ms),
  KEY idx_punishment_ledger_expires (expires_at_ms),
  KEY idx_punishment_ledger_revoked (revoked_at_ms),
  KEY idx_punishment_ledger_updated (updated_at_ms)
);

INSERT IGNORE INTO moderation_punishment_ledger (
  punishment_id, punishment_type, scope, server_id, subject_uuid, subject_name, reason,
  actor_name, created_at_ms, starts_at_ms, expires_at_ms, revoked_at_ms, updated_at_ms, metadata_json
)
SELECT CONCAT('P-L-', LPAD(HEX(id), 16, '0')),
  CASE LOWER(type) WHEN 'tempban' THEN 'BAN' WHEN 'tempmute' THEN 'MUTE' ELSE UPPER(type) END,
  scope, server_id, uuid, name, reason, actor, created_at_ms, created_at_ms, expires_at_ms,
  CASE WHEN active = FALSE THEN created_at_ms ELSE NULL END, created_at_ms, '{"legacy":"moderation_punishments"}'
FROM moderation_punishments;

INSERT IGNORE INTO moderation_punishment_ledger (
  punishment_id, punishment_type, scope, subject_uuid, subject_name, reason, actor_name,
  created_at_ms, starts_at_ms, updated_at_ms, metadata_json
)
SELECT CONCAT('P-L-A', LPAD(HEX(id), 15, '0')), 'WARN', 'GLOBAL', uuid, name, reason, actor,
  created_at_ms, created_at_ms, created_at_ms, '{"legacy":"moderation_warnings"}'
FROM moderation_warnings;

INSERT IGNORE INTO moderation_punishment_ledger (
  punishment_id, punishment_type, scope, server_id, subject_uuid, subject_name, reason,
  actor_name, created_at_ms, starts_at_ms, expires_at_ms, updated_at_ms, metadata_json
)
SELECT CONCAT('P-L-B', LEFT(SHA1(CONCAT(server_id, ':', uuid, ':', created_at_ms)), 15)), 'JAIL', 'SERVER',
  server_id, uuid, name, reason, actor, created_at_ms, created_at_ms, expires_at_ms, created_at_ms,
  '{"legacy":"moderation_jails"}'
FROM moderation_jails;

CREATE OR REPLACE VIEW paradigm_v_punishments_public AS
SELECT punishment_id, punishment_type,
  CASE WHEN revoked_at_ms IS NOT NULL THEN 'REVOKED'
       WHEN starts_at_ms > (UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) THEN 'PENDING'
       WHEN expires_at_ms IS NOT NULL AND expires_at_ms <= (UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) THEN 'EXPIRED'
       ELSE 'ACTIVE' END AS status,
  scope, network_id, server_id, subject_uuid AS player_uuid, subject_name AS player_name,
  reason, actor_uuid, actor_name, created_at_ms, starts_at_ms, expires_at_ms,
  revoked_at_ms, revoked_by_uuid, revoked_by_name, revoke_reason
FROM moderation_punishment_ledger;

CREATE OR REPLACE VIEW paradigm_v_active_bans AS
SELECT * FROM paradigm_v_punishments_public WHERE punishment_type = 'BAN' AND status = 'ACTIVE';

CREATE OR REPLACE VIEW paradigm_v_active_ip_bans AS
SELECT punishment_id, punishment_type,
  'ACTIVE' AS status, scope, network_id, server_id,
  CONCAT('IP:', LEFT(subject_ip_hash, 12)) AS masked_ip_subject, reason, actor_uuid, actor_name,
  created_at_ms, starts_at_ms, expires_at_ms, revoked_at_ms, revoked_by_uuid, revoked_by_name, revoke_reason
FROM moderation_punishment_ledger
WHERE punishment_type = 'IP_BAN' AND revoked_at_ms IS NULL
  AND starts_at_ms <= (UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
  AND (expires_at_ms IS NULL OR expires_at_ms > (UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000));

CREATE OR REPLACE VIEW paradigm_v_player_punishment_history AS
SELECT * FROM paradigm_v_punishments_public WHERE player_uuid IS NOT NULL;

CREATE OR REPLACE VIEW paradigm_v_active_punishments AS
SELECT * FROM paradigm_v_punishments_public WHERE status = 'ACTIVE';

CREATE OR REPLACE VIEW paradigm_v_punishment_summary AS
SELECT punishment_type, status, scope, network_id, server_id, COUNT(*) AS punishment_count
FROM paradigm_v_punishments_public GROUP BY punishment_type, status, scope, network_id, server_id;
