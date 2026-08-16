-- Allow multiple assignments of the same group to one user when contexts/expiry differ.
-- V5 already backfills a stable assignment_id and creates a unique index on it.
ALTER TABLE permission_user_groups DROP PRIMARY KEY;
CREATE INDEX idx_permission_user_groups_uuid_group ON permission_user_groups(uuid, group_name);
