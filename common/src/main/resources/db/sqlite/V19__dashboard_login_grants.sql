CREATE TABLE IF NOT EXISTS dashboard_login_grants (
    token_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    network_id VARCHAR(64) NOT NULL,
    issuer_server_id VARCHAR(64) NOT NULL,
    host_server_id VARCHAR(64) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    player_name VARCHAR(64) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_dashboard_login_expiry ON dashboard_login_grants(expires_at_ms);
