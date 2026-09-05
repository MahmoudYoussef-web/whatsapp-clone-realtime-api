-- V3__groups.sql — group conversations reuse conversation_participants
-- (N members, ADMIN role). Only metadata columns are new.
ALTER TABLE conversations
    ADD COLUMN name VARCHAR(255),
    ADD COLUMN avatar_object_key VARCHAR(512),
    ADD COLUMN created_by VARCHAR(255) REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX idx_conversations_type ON conversations (type);
