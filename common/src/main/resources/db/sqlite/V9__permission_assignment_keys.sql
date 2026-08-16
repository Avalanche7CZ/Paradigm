-- SQLite cannot drop a composite primary key in place, so rebuild the table around assignment_id.
DROP VIEW IF EXISTS paradigm_v_active_ip_bans;

CREATE TABLE permission_user_groups_v9 (
  uuid TEXT NOT NULL,
  group_name TEXT NOT NULL,
  expires_at_ms INTEGER,
  assigned_by TEXT,
  assigned_at_ms INTEGER,
  contexts TEXT,
  context_hash TEXT NOT NULL DEFAULT '',
  assignment_id TEXT PRIMARY KEY
);

INSERT OR REPLACE INTO permission_user_groups_v9(
  uuid, group_name, expires_at_ms, assigned_by, assigned_at_ms, contexts, context_hash, assignment_id
)
SELECT
  uuid,
  group_name,
  expires_at_ms,
  assigned_by,
  assigned_at_ms,
  contexts,
  COALESCE(context_hash, ''),
  CASE
    WHEN assignment_id IS NULL OR assignment_id = '' THEN
      'legacy:user_group:' || uuid || ':' || group_name || ':' || COALESCE(context_hash, '') || ':' || COALESCE(expires_at_ms, '') || ':' || COALESCE(assigned_at_ms, 0)
    ELSE assignment_id
  END
FROM permission_user_groups;

DROP TABLE permission_user_groups;
ALTER TABLE permission_user_groups_v9 RENAME TO permission_user_groups;
CREATE INDEX IF NOT EXISTS idx_permission_user_groups_uuid ON permission_user_groups(uuid);
CREATE INDEX IF NOT EXISTS idx_permission_user_groups_context ON permission_user_groups(context_hash);
CREATE INDEX IF NOT EXISTS idx_permission_user_groups_uuid_group ON permission_user_groups(uuid, group_name);

CREATE VIEW paradigm_v_active_ip_bans AS
SELECT punishment_id, punishment_type, 'ACTIVE' AS status, scope, network_id, server_id,
  'IP:' || substr(subject_ip_hash, 1, 12) AS masked_ip_subject, reason, actor_uuid, actor_name,
  created_at_ms, starts_at_ms, expires_at_ms, revoked_at_ms, revoked_by_uuid, revoked_by_name, revoke_reason
FROM moderation_punishment_ledger
WHERE punishment_type = 'IP_BAN' AND revoked_at_ms IS NULL AND starts_at_ms <= (strftime('%s','now') * 1000)
  AND (expires_at_ms IS NULL OR expires_at_ms > (strftime('%s','now') * 1000));
