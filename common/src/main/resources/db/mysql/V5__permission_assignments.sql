ALTER TABLE permission_group_permissions ADD COLUMN contexts TEXT NULL;
ALTER TABLE permission_group_permissions ADD COLUMN context_hash VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE permission_user_permissions ADD COLUMN contexts TEXT NULL;
ALTER TABLE permission_user_permissions ADD COLUMN context_hash VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE permission_user_groups ADD COLUMN contexts TEXT NULL;
ALTER TABLE permission_user_groups ADD COLUMN context_hash VARCHAR(255) NOT NULL DEFAULT '';

UPDATE permission_group_permissions
SET contexts = CONCAT('{"server":"', server_id, '"}'), context_hash = CONCAT('server=', server_id)
WHERE server_id IS NOT NULL AND server_id <> '' AND (contexts IS NULL OR contexts = '');

UPDATE permission_user_permissions
SET contexts = CONCAT('{"server":"', server_id, '"}'), context_hash = CONCAT('server=', server_id)
WHERE server_id IS NOT NULL AND server_id <> '' AND (contexts IS NULL OR contexts = '');

CREATE INDEX idx_permission_group_permissions_context ON permission_group_permissions(context_hash);
CREATE INDEX idx_permission_user_permissions_context ON permission_user_permissions(context_hash);
CREATE INDEX idx_permission_user_groups_context ON permission_user_groups(context_hash);

ALTER TABLE permission_group_permissions ADD COLUMN assignment_id VARCHAR(191) NULL;
ALTER TABLE permission_user_permissions ADD COLUMN assignment_id VARCHAR(191) NULL;
ALTER TABLE permission_user_groups ADD COLUMN assignment_id VARCHAR(191) NULL;

UPDATE permission_group_permissions
SET assignment_id = CONCAT('legacy:group_permission:', group_name, ':', permission, ':', denied, ':', context_hash, ':', COALESCE(expires_at_ms, ''))
WHERE assignment_id IS NULL OR assignment_id = '';

UPDATE permission_user_permissions
SET assignment_id = CONCAT('legacy:user_permission:', uuid, ':', permission, ':', denied, ':', context_hash, ':', COALESCE(expires_at_ms, ''))
WHERE assignment_id IS NULL OR assignment_id = '';

UPDATE permission_user_groups
SET assignment_id = CONCAT('legacy:user_group:', uuid, ':', group_name, ':', context_hash, ':', COALESCE(expires_at_ms, ''), ':', assigned_at_ms)
WHERE assignment_id IS NULL OR assignment_id = '';

CREATE UNIQUE INDEX idx_permission_group_permissions_assignment_id ON permission_group_permissions(assignment_id);
CREATE UNIQUE INDEX idx_permission_user_permissions_assignment_id ON permission_user_permissions(assignment_id);
CREATE UNIQUE INDEX idx_permission_user_groups_assignment_id ON permission_user_groups(assignment_id);
