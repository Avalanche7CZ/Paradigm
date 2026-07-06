CREATE TABLE IF NOT EXISTS permission_groups (
  name TEXT PRIMARY KEY,
  description TEXT,
  prefix TEXT,
  suffix TEXT,
  weight INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS permission_group_parents (
  group_name TEXT NOT NULL,
  parent_name TEXT NOT NULL,
  PRIMARY KEY (group_name, parent_name)
);

CREATE TABLE IF NOT EXISTS permission_group_permissions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  group_name TEXT NOT NULL,
  server_id TEXT NULL,
  permission TEXT NOT NULL,
  denied INTEGER NOT NULL DEFAULT 0,
  expires_at_ms INTEGER
);

CREATE TABLE IF NOT EXISTS permission_users (
  uuid TEXT PRIMARY KEY,
  name TEXT
);

CREATE TABLE IF NOT EXISTS permission_user_groups (
  uuid TEXT NOT NULL,
  group_name TEXT NOT NULL,
  expires_at_ms INTEGER,
  assigned_by TEXT,
  assigned_at_ms INTEGER,
  PRIMARY KEY (uuid, group_name)
);

CREATE TABLE IF NOT EXISTS permission_user_permissions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid TEXT NOT NULL,
  server_id TEXT NULL,
  permission TEXT NOT NULL,
  denied INTEGER NOT NULL DEFAULT 0,
  expires_at_ms INTEGER
);

CREATE INDEX IF NOT EXISTS idx_permission_user_groups_uuid ON permission_user_groups(uuid);
CREATE INDEX IF NOT EXISTS idx_permission_user_permissions_uuid ON permission_user_permissions(uuid);
CREATE INDEX IF NOT EXISTS idx_permission_user_permissions_server ON permission_user_permissions(server_id);
CREATE INDEX IF NOT EXISTS idx_permission_group_permissions_server ON permission_group_permissions(server_id);
