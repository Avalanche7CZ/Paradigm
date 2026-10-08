ALTER TABLE moderation_jails ADD COLUMN punishment_id TEXT NULL;
CREATE TABLE IF NOT EXISTS moderation_jail_subjects (
  server_id TEXT NOT NULL,
  uuid TEXT NOT NULL,
  PRIMARY KEY (server_id, uuid)
);
UPDATE moderation_jails SET punishment_id = (
  SELECT punishment_id FROM moderation_punishment_ledger p
  WHERE p.punishment_type = 'JAIL' AND p.scope = 'SERVER'
    AND p.server_id = moderation_jails.server_id AND p.subject_uuid = moderation_jails.uuid
    AND p.revoked_at_ms IS NULL AND p.created_at_ms = moderation_jails.created_at_ms
  ORDER BY p.punishment_id
  LIMIT 1
) WHERE punishment_id IS NULL;
UPDATE moderation_jails SET punishment_id = (
  SELECT punishment_id FROM moderation_punishment_ledger p
  WHERE p.punishment_type = 'JAIL' AND p.scope = 'SERVER'
    AND p.server_id = moderation_jails.server_id AND p.subject_uuid = moderation_jails.uuid
    AND p.revoked_at_ms IS NULL
  ORDER BY p.created_at_ms DESC, p.punishment_id
  LIMIT 1
) WHERE punishment_id IS NULL;
