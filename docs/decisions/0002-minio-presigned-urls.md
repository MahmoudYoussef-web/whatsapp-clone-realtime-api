# ADR-0002: MinIO presigned URLs instead of media byte arrays

**Status:** Accepted · **Phase:** 1 · **Date:** 2026-08-02

## Context

The original `Message` entity stored raw media as `byte[]` in Postgres and the REST API
returned base64 in JSON (`media: ['/9j/4AA...']`). Consequences: database bloat, no size
control, no browser-friendly streaming, huge JSON payloads, and WebSocket notifications
that cannot carry large payloads.

## Decision

- Media lives in MinIO (S3-compatible) under `users/{userId}/{conversationId}/{uuid}.{ext}`.
- The API stores only metadata (`object_key`, `mime_type`, `size_bytes`) and returns
  **presigned URLs** (TTL 15 min, configured) that the browser can use directly.
- Message/attachment type (`IMAGE|VIDEO|AUDIO|FILE`) is derived from the MIME type on upload.
- Uploads are validated: MIME + extension whitelist, per-category size limits
  (image 10 MB, video 100 MB, audio 25 MB, document 25 MB), enforced via `MediaTypeValidator`.
- WebSocket notifications carry only the DTO with presigned URLs — never bytes.

## Consequences

- Scalable storage (S3-compatible: swap MinIO for AWS S3 by config), small DB rows,
  browser-native `<img>/<video>/<audio>` rendering, expired URLs force re-fetch.
- Requires MinIO running; bucket is auto-created at startup (`MinioConfig`).
