CREATE TABLE IF NOT EXISTS permission_tracks (
  name VARCHAR(64) PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS permission_track_members (
  track_name VARCHAR(64) NOT NULL,
  group_name VARCHAR(64) NOT NULL,
  position INTEGER NOT NULL,
  PRIMARY KEY (track_name, group_name),
  UNIQUE KEY uk_permission_track_position (track_name, position),
  KEY idx_permission_track_members_group (group_name)
);
