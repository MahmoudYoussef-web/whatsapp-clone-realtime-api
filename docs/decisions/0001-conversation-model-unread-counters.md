# ADR-0001: Conversation model with explicit participants and unread counters

**Status:** Accepted · **Phase:** 1 · **Date:** 2026-08-02

## Context

The original model had a single `Chat` entity with `sender_id`/`receiver_id` columns and a
`recipientUnreadCount` computed in Java (load chat → increment → save), which is a
read-modify-write race: two concurrent senders can both read `0` and write `1`, losing counts.
"Seen" logic also marked every message in a chat as seen, including the user's own.

## Decision

- Replace `Chat` with `Conversation` + `ConversationParticipant`.
- `unread_count` is a **DB column** on `conversation_participants`, mutated only by atomic
  `UPDATE conversation_participants SET unread_count = unread_count + 1 WHERE ...` statements
  (JPQL bulk update, `@Modifying`). No read-modify-write in Java.
- Private conversations are idempotent: `findBetweenUsers(...)` returns the existing one.
- READ marks only messages where `sender_id <> :viewerId` and resets the viewer's counter —
  a user's own messages are never marked as read by themselves.

## Consequences

- Race-safe counters under concurrency (single statement per message, Postgres row locks).
- Slightly heavier DTO mapping (participants joined), covered by
  `SchemaMigrationIntegrationTest.unreadCounterIsIncrementedForReceiverOnlyAndResetOnRead`.
