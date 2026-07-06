CREATE TABLE IF NOT EXISTS permission_groups (
  name VARCHAR(64) PRIMARY KEY,
  description TEXT,
  prefix TEXT,
  suffix TEXT,
  weight INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS permission_group_parents (
  group_name VARCHAR(64) NOT NULL,
  parent_name VARCHAR(64) NOT NULL,
  PRIMARY KEY (group_name, parent_name)
);

CREATE TABLE IF NOT EXISTS permission_group_permissions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  group_name VARCHAR(64) NOT NULL,
  server_id VARCHAR(64) NULL,
  permission VARCHAR(255) NOT NULL,
  denied BOOLEAN NOT NULL DEFAULT FALSE,
  expires_at_ms BIGINT
);

CREATE TABLE IF NOT EXISTS permission_users (
  uuid VARCHAR(36) PRIMARY KEY,
  name VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS permission_user_groups (
  uuid VARCHAR(36) NOT NULL,
  group_name VARCHAR(64) NOT NULL,
  expires_at_ms BIGINT,
  assigned_by VARCHAR(64),
  assigned_at_ms BIGINT,
  PRIMARY KEY (uuid, group_name)
);

CREATE TABLE IF NOT EXISTS permission_user_permissions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  uuid VARCHAR(36) NOT NULL,
  server_id VARCHAR(64) NULL,
  permission VARCHAR(255) NOT NULL,
  denied BOOLEAN NOT NULL DEFAULT FALSE,
  expires_at_ms BIGINT
);

CREATE INDEX idx_permission_user_groups_uuid ON permission_user_groups(uuid);
CREATE INDEX idx_permission_user_permissions_uuid ON permission_user_permissions(uuid);
CREATE INDEX idx_permission_user_permissions_server ON permission_user_permissions(server_id);
CREATE INDEX idx_permission_group_permissions_server ON permission_group_permissions(server_id);
