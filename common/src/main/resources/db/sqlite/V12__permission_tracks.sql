CREATE TABLE IF NOT EXISTS permission_tracks (
  name TEXT PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS permission_track_members (
  track_name TEXT NOT NULL,
  group_name TEXT NOT NULL,
  position INTEGER NOT NULL,
  PRIMARY KEY (track_name, group_name),
  UNIQUE (track_name, position)
);

CREATE INDEX IF NOT EXISTS idx_permission_track_members_group ON permission_track_members(group_name);
