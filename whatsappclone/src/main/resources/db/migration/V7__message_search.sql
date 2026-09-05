-- V5__message_search.sql — trigram index for in-conversation text search.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_messages_content_trgm ON messages USING gin (content gin_trgm_ops);
