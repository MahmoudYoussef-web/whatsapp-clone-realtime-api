-- V10__attachment_thumbnails.sql
ALTER TABLE attachments
    ADD COLUMN thumbnail_object_key VARCHAR(512);
