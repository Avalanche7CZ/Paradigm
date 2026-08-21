CREATE TABLE IF NOT EXISTS tickets (
  ticket_id TEXT PRIMARY KEY,
  ticket_key TEXT NOT NULL,
  network_id TEXT NOT NULL,
  origin_server_id TEXT,
  creator_uuid TEXT,
  creator_name TEXT,
  category TEXT NOT NULL DEFAULT 'general',
  subject TEXT,
  status TEXT NOT NULL DEFAULT 'OPEN',
  priority TEXT NOT NULL DEFAULT 'NORMAL',
  assignee_uuid TEXT,
  assignee_name TEXT,
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL,
  resolved_at_ms INTEGER,
  closed_at_ms INTEGER,
  last_activity_at_ms INTEGER NOT NULL,
  revision INTEGER NOT NULL DEFAULT 0,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  UNIQUE (network_id, ticket_key)
);

CREATE INDEX IF NOT EXISTS idx_tickets_network_status ON tickets(network_id, status);
CREATE INDEX IF NOT EXISTS idx_tickets_network_creator ON tickets(network_id, creator_uuid);
CREATE INDEX IF NOT EXISTS idx_tickets_network_assignee ON tickets(network_id, assignee_uuid);
CREATE INDEX IF NOT EXISTS idx_tickets_network_priority ON tickets(network_id, priority);
CREATE INDEX IF NOT EXISTS idx_tickets_network_origin ON tickets(network_id, origin_server_id);
CREATE INDEX IF NOT EXISTS idx_tickets_updated ON tickets(updated_at_ms);

CREATE TABLE IF NOT EXISTS ticket_messages (
  message_id TEXT PRIMARY KEY,
  ticket_id TEXT NOT NULL,
  network_id TEXT NOT NULL,
  ticket_key TEXT NOT NULL,
  author_type TEXT NOT NULL DEFAULT 'PLAYER',
  author_uuid TEXT,
  author_name TEXT,
  server_id TEXT,
  message_text TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ticket_messages_ticket ON ticket_messages(ticket_id, created_at_ms);
CREATE INDEX IF NOT EXISTS idx_ticket_messages_key ON ticket_messages(network_id, ticket_key);

CREATE TABLE IF NOT EXISTS ticket_events (
  event_id TEXT PRIMARY KEY,
  ticket_id TEXT NOT NULL,
  network_id TEXT NOT NULL,
  ticket_key TEXT NOT NULL,
  event_type TEXT NOT NULL,
  actor_uuid TEXT,
  actor_name TEXT,
  server_id TEXT,
  old_value TEXT,
  new_value TEXT,
  created_at_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ticket_events_ticket ON ticket_events(ticket_id, created_at_ms);
CREATE INDEX IF NOT EXISTS idx_ticket_events_feed ON ticket_events(network_id, created_at_ms);

CREATE TABLE IF NOT EXISTS tickets_sequence (
  network_id TEXT PRIMARY KEY,
  next_value INTEGER NOT NULL
);

DROP VIEW IF EXISTS paradigm_v_open_tickets;
CREATE VIEW paradigm_v_open_tickets AS
SELECT ticket_key, network_id, origin_server_id, creator_uuid, creator_name, category, subject,
  status, priority, assignee_uuid, assignee_name, created_at_ms, updated_at_ms, last_activity_at_ms
FROM tickets
WHERE status IN ('OPEN', 'IN_PROGRESS', 'WAITING_PLAYER', 'WAITING_STAFF');

DROP VIEW IF EXISTS paradigm_v_ticket_summary;
CREATE VIEW paradigm_v_ticket_summary AS
SELECT network_id, origin_server_id, status, priority, category, COUNT(*) AS ticket_count
FROM tickets
GROUP BY network_id, origin_server_id, status, priority, category;

DROP VIEW IF EXISTS paradigm_v_ticket_messages;
CREATE VIEW paradigm_v_ticket_messages AS
SELECT m.message_id, m.network_id, m.ticket_key, t.category, t.status AS ticket_status,
  m.author_type, m.author_uuid, m.author_name, m.server_id, m.message_text, m.created_at_ms
FROM ticket_messages m
JOIN tickets t ON t.ticket_id = m.ticket_id;
