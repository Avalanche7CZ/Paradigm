CREATE TABLE IF NOT EXISTS tickets (
  ticket_id VARCHAR(64) PRIMARY KEY,
  ticket_key VARCHAR(24) NOT NULL,
  network_id VARCHAR(64) NOT NULL,
  origin_server_id VARCHAR(64),
  creator_uuid VARCHAR(36),
  creator_name VARCHAR(64),
  category VARCHAR(64) NOT NULL DEFAULT 'general',
  subject VARCHAR(255),
  status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
  assignee_uuid VARCHAR(36),
  assignee_name VARCHAR(64),
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  resolved_at_ms BIGINT,
  closed_at_ms BIGINT,
  last_activity_at_ms BIGINT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  metadata_json TEXT NOT NULL,
  UNIQUE KEY idx_tickets_network_key (network_id, ticket_key),
  KEY idx_tickets_network_status (network_id, status),
  KEY idx_tickets_network_creator (network_id, creator_uuid),
  KEY idx_tickets_network_assignee (network_id, assignee_uuid),
  KEY idx_tickets_network_priority (network_id, priority),
  KEY idx_tickets_network_origin (network_id, origin_server_id),
  KEY idx_tickets_updated (updated_at_ms)
);

CREATE TABLE IF NOT EXISTS ticket_messages (
  message_id VARCHAR(64) PRIMARY KEY,
  ticket_id VARCHAR(64) NOT NULL,
  network_id VARCHAR(64) NOT NULL,
  ticket_key VARCHAR(24) NOT NULL,
  author_type VARCHAR(16) NOT NULL DEFAULT 'PLAYER',
  author_uuid VARCHAR(36),
  author_name VARCHAR(64),
  server_id VARCHAR(64),
  message_text TEXT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  KEY idx_ticket_messages_ticket (ticket_id, created_at_ms),
  KEY idx_ticket_messages_key (network_id, ticket_key)
);

CREATE TABLE IF NOT EXISTS ticket_events (
  event_id VARCHAR(64) PRIMARY KEY,
  ticket_id VARCHAR(64) NOT NULL,
  network_id VARCHAR(64) NOT NULL,
  ticket_key VARCHAR(24) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  actor_uuid VARCHAR(36),
  actor_name VARCHAR(64),
  server_id VARCHAR(64),
  old_value VARCHAR(255),
  new_value VARCHAR(255),
  created_at_ms BIGINT NOT NULL,
  KEY idx_ticket_events_ticket (ticket_id, created_at_ms),
  KEY idx_ticket_events_feed (network_id, created_at_ms)
);

CREATE TABLE IF NOT EXISTS tickets_sequence (
  network_id VARCHAR(64) PRIMARY KEY,
  next_value BIGINT NOT NULL
);

CREATE OR REPLACE VIEW paradigm_v_open_tickets AS
SELECT ticket_key, network_id, origin_server_id, creator_uuid, creator_name, category, subject,
  status, priority, assignee_uuid, assignee_name, created_at_ms, updated_at_ms, last_activity_at_ms
FROM tickets
WHERE status IN ('OPEN', 'IN_PROGRESS', 'WAITING_PLAYER', 'WAITING_STAFF');

CREATE OR REPLACE VIEW paradigm_v_ticket_summary AS
SELECT network_id, origin_server_id, status, priority, category, COUNT(*) AS ticket_count
FROM tickets
GROUP BY network_id, origin_server_id, status, priority, category;

CREATE OR REPLACE VIEW paradigm_v_ticket_messages AS
SELECT m.message_id, m.network_id, m.ticket_key, t.category, t.status AS ticket_status,
  m.author_type, m.author_uuid, m.author_name, m.server_id, m.message_text, m.created_at_ms
FROM ticket_messages m
JOIN tickets t ON t.ticket_id = m.ticket_id;
