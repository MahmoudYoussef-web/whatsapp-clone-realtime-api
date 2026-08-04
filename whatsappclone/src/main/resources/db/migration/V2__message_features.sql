ALTER TABLE messages
    ADD COLUMN reply_to_message_id BIGINT REFERENCES messages (id) ON DELETE SET NULL,
    ADD COLUMN edited BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN edited_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN deleted_for_sender BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_for_everyone BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_messages_reply_to ON messages (reply_to_message_id);
