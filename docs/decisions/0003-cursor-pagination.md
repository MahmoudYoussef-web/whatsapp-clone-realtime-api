# ADR-0003: Cursor pagination for message history

**Status:** Accepted · **Phase:** 1 · **Date:** 2026-08-02

## Context

The original API returned the **entire** conversation history in one response
(`GET /api/v1/messages/chat/{id}`). Offsets-based paging is unstable under concurrent
inserts (duplicates/skips), and loading all messages never scales.

## Decision

- `GET /api/v1/conversations/{id}/messages?before={messageId}&limit=30` returns the page
  **strictly older** than `before` (a message id, the cursor), newest first.
- `hasMore` + `nextCursor` tell the client whether an older page exists and what to pass next.
- Implementation: the repository requests `limit + 1` rows; the extra row proves `hasMore`.
- Message ids are monotonically increasing (`IDENTITY`), so the cursor is stable even when
  new messages arrive while scrolling back.

## Consequences

- O(limit) queries, stable pages, natural "load older on scroll" UX (implemented in the
  Angular chat window with scroll-position preservation).
- Verified by `SchemaMigrationIntegrationTest.cursorPaginationReturnsNewestFirstWithHasMore`.
