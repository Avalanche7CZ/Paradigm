CREATE TABLE IF NOT EXISTS discord_ticket_reply_delivery (
    network_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    guild_id TEXT NOT NULL,
    next_offset INTEGER NOT NULL DEFAULT 0,
    phase TEXT NOT NULL DEFAULT 'READY',
    retry_at_ms BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (network_id, message_id)
);

CREATE INDEX idx_discord_ticket_reply_phase ON discord_ticket_reply_delivery (network_id, guild_id, phase);

CREATE TABLE IF NOT EXISTS discord_ticket_reply_rejections (
    network_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    PRIMARY KEY (network_id, message_id)
);

INSERT INTO discord_ticket_reply_delivery (network_id, message_id, guild_id, phase)
SELECT m.network_id, m.message_id, r.guild_id, 'SKIPPED'
FROM discord_ticket_rooms r
JOIN ticket_messages m ON m.network_id = r.network_id AND m.ticket_id = r.ticket_id
WHERE r.phase = 'BOUND' AND m.author_type IN ('PLAYER', 'STAFF')
ON CONFLICT(network_id, message_id) DO NOTHING;
