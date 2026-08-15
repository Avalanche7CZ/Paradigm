ALTER TABLE server_instances ADD COLUMN mod_version VARCHAR(32);
ALTER TABLE server_instances ADD COLUMN minecraft_version VARCHAR(32);
ALTER TABLE server_instances ADD COLUMN loader VARCHAR(32);
ALTER TABLE server_instances ADD COLUMN schema_fingerprint VARCHAR(32);
