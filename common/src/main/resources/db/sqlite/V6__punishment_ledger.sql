CREATE TABLE IF NOT EXISTS moderation_punishment_ledger (
  punishment_id TEXT PRIMARY KEY,
  punishment_type TEXT NOT NULL,
  scope TEXT NOT NULL DEFAULT 'GLOBAL',
  network_id TEXT,
  server_id TEXT,
  subject_uuid TEXT,
  subject_name TEXT,
  subject_ip_hash TEXT,
  subject_ip_address TEXT,
  reason TEXT,
  actor_uuid TEXT,
  actor_name TEXT,
  created_at_ms INTEGER NOT NULL,
  starts_at_ms INTEGER NOT NULL,
  expires_at_ms INTEGER,
  revoked_at_ms INTEGER,
  revoked_by_uuid TEXT,
  revoked_by_name TEXT,
  revoke_reason TEXT,
  updated_at_ms INTEGER NOT NULL,
  metadata_json TEXT NOT NULL DEFAULT '{}'
);

INSERT OR IGNORE INTO moderation_punishment_ledger (
  punishment_id, punishment_type, scope, server_id, subject_uuid, subject_name, reason,
  actor_name, created_at_ms, starts_at_ms, expires_at_ms, revoked_at_ms, updated_at_ms, metadata_json
)
SELECT 'P-L-' || printf('%016X', id),
  CASE LOWER(type) WHEN 'tempban' THEN 'BAN' WHEN 'tempmute' THEN 'MUTE' ELSE UPPER(type) END,
  scope, server_id, uuid, name, reason, actor, created_at_ms, created_at_ms, expires_at_ms,
  CASE WHEN active = 0 THEN created_at_ms ELSE NULL END, created_at_ms, '{"legacy":"moderation_punishments"}'
FROM moderation_punishments;

INSERT OR IGNORE INTO moderation_punishment_ledger (
  punishment_id, punishment_type, scope, subject_uuid, subject_name, reason, actor_name,
  created_at_ms, starts_at_ms, updated_at_ms, metadata_json
)
SELECT 'P-L-A' || printf('%015X', id), 'WARN', 'GLOBAL', uuid, name, reason, actor,
  created_at_ms, created_at_ms, created_at_ms, '{"legacy":"moderation_warnings"}'
FROM moderation_warnings;

INSERT OR IGNORE INTO moderation_punishment_ledger (
  punishment_id, punishment_type, scope, server_id, subject_uuid, subject_name, reason,
  actor_name, created_at_ms, starts_at_ms, expires_at_ms, updated_at_ms, metadata_json
)
SELECT 'P-L-B' || substr(hex(server_id || ':' || uuid || ':' || created_at_ms), 1, 15), 'JAIL', 'SERVER',
  server_id, uuid, name, reason, actor, created_at_ms, created_at_ms, expires_at_ms, created_at_ms,
  '{"legacy":"moderation_jails"}'
FROM moderation_jails;

CREATE UNIQUE INDEX IF NOT EXISTS idx_punishment_ledger_id ON moderation_punishment_ledger(punishment_id);
CREATE INDEX IF NOT EXISTS idx_punishment_ledger_subject ON moderation_punishment_ledger(subject_uuid, punishment_type, revoked_at_ms);
CREATE INDEX IF NOT EXISTS idx_punishment_ledger_ip ON moderation_punishment_ledger(subject_ip_hash, punishment_type, revoked_at_ms);
CREATE INDEX IF NOT EXISTS idx_punishment_ledger_network ON moderation_punishment_ledger(network_id);
CREATE INDEX IF NOT EXISTS idx_punishment_ledger_server ON moderation_punishment_ledger(server_id);
CREATE INDEX IF NOT EXISTS idx_punishment_ledger_created ON moderation_punishment_ledger(created_at_ms);
CREATE INDEX IF NOT EXISTS idx_punishment_ledger_expires ON moderation_punishment_ledger(expires_at_ms);
CREATE INDEX IF NOT EXISTS idx_punishment_ledger_revoked ON moderation_punishment_ledger(revoked_at_ms);
CREATE INDEX IF NOT EXISTS idx_punishment_ledger_updated ON moderation_punishment_ledger(updated_at_ms);

DROP VIEW IF EXISTS paradigm_v_punishments_public;
CREATE VIEW paradigm_v_punishments_public AS
SELECT punishment_id, punishment_type,
  CASE WHEN revoked_at_ms IS NOT NULL THEN 'REVOKED'
       WHEN starts_at_ms > (strftime('%s','now') * 1000) THEN 'PENDING'
       WHEN expires_at_ms IS NOT NULL AND expires_at_ms <= (strftime('%s','now') * 1000) THEN 'EXPIRED'
       ELSE 'ACTIVE' END AS status,
  scope, network_id, server_id, subject_uuid AS player_uuid, subject_name AS player_name,
  reason, actor_uuid, actor_name, created_at_ms, starts_at_ms, expires_at_ms,
  revoked_at_ms, revoked_by_uuid, revoked_by_name, revoke_reason
FROM moderation_punishment_ledger;

DROP VIEW IF EXISTS paradigm_v_active_bans;
CREATE VIEW paradigm_v_active_bans AS
SELECT * FROM paradigm_v_punishments_public WHERE punishment_type = 'BAN' AND status = 'ACTIVE';

DROP VIEW IF EXISTS paradigm_v_active_ip_bans;
CREATE VIEW paradigm_v_active_ip_bans AS
SELECT punishment_id, punishment_type, status, scope, network_id, server_id,
  'IP:' || substr(subject_ip_hash, 1, 12) AS masked_ip_subject, reason, actor_uuid, actor_name,
  created_at_ms, starts_at_ms, expires_at_ms, revoked_at_ms, revoked_by_uuid, revoked_by_name, revoke_reason
FROM moderation_punishment_ledger
WHERE punishment_type = 'IP_BAN' AND revoked_at_ms IS NULL AND starts_at_ms <= (strftime('%s','now') * 1000)
  AND (expires_at_ms IS NULL OR expires_at_ms > (strftime('%s','now') * 1000));

DROP VIEW IF EXISTS paradigm_v_player_punishment_history;
CREATE VIEW paradigm_v_player_punishment_history AS
SELECT * FROM paradigm_v_punishments_public WHERE player_uuid IS NOT NULL;

DROP VIEW IF EXISTS paradigm_v_active_punishments;
CREATE VIEW paradigm_v_active_punishments AS
SELECT * FROM paradigm_v_punishments_public WHERE status = 'ACTIVE';

DROP VIEW IF EXISTS paradigm_v_punishment_summary;
CREATE VIEW paradigm_v_punishment_summary AS
SELECT punishment_type, status, scope, network_id, server_id, COUNT(*) AS punishment_count
FROM paradigm_v_punishments_public GROUP BY punishment_type, status, scope, network_id, server_id;
