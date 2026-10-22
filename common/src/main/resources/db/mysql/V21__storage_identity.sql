CREATE TABLE IF NOT EXISTS storage_metadata (
    metadata_key VARCHAR(64) PRIMARY KEY,
    metadata_value VARCHAR(64) NOT NULL
) ENGINE=InnoDB;
INSERT IGNORE INTO storage_metadata(metadata_key, metadata_value) VALUES('database_id', UUID());
