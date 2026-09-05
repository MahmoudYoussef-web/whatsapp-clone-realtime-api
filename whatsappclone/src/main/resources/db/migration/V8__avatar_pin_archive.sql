-- V8__avatar_pin_archive.sql
ALTER TABLE users
    ADD COLUMN avatar_object_key VARCHAR(512);

ALTER TABLE conversation_participants
    ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
