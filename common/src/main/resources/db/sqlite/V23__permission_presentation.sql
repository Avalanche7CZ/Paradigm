ALTER TABLE permission_groups ADD COLUMN chat_name_format TEXT;
ALTER TABLE permission_groups ADD COLUMN chat_message_format TEXT;
ALTER TABLE permission_users ADD COLUMN display_group VARCHAR(64);
