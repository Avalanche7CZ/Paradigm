CREATE TABLE IF NOT EXISTS permission_settings (id INTEGER PRIMARY KEY, default_group VARCHAR(64) NOT NULL) ENGINE=InnoDB;
INSERT IGNORE INTO permission_settings(id, default_group) VALUES(1, 'default');
