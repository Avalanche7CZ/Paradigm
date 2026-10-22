CREATE TABLE IF NOT EXISTS permission_sync_state (
  id INTEGER PRIMARY KEY,
  revision BIGINT NOT NULL,
  definitions_revision BIGINT NOT NULL
) ENGINE=InnoDB;

INSERT IGNORE INTO permission_sync_state(id, revision, definitions_revision) VALUES(1, 0, 0);

CREATE TABLE IF NOT EXISTS permission_user_versions (
  uuid VARCHAR(36) PRIMARY KEY,
  revision BIGINT NOT NULL
) ENGINE=InnoDB;

CREATE INDEX idx_permission_user_revision ON permission_user_versions(revision);
