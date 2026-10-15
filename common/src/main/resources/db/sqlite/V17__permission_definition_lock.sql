CREATE TABLE IF NOT EXISTS permission_definition_lock (
  id INTEGER PRIMARY KEY
);

INSERT OR IGNORE INTO permission_definition_lock(id) VALUES(1);

CREATE TABLE IF NOT EXISTS permission_definition_versions (
  kind VARCHAR(8) NOT NULL,
  name VARCHAR(64) NOT NULL,
  revision BIGINT NOT NULL,
  PRIMARY KEY (kind, name)
);
