# ADR-0010: Receiver-driven delivery, replies, edits and soft deletes

**Status:** Accepted · **Phase:** 2 · **Date:** 2026-08-02

## Context

Phase 1 marked a message DELIVERED immediately after the server-side WS push
("best-effort"), which made the tick meaningless (the receiver may never have
received it) and left no room for offline handling. Phase 2 adds replies,
edit/delete and a truthful delivery lifecycle, plus deletion semantics that must
never re-expose deleted content.

## Decision

### Delivery lifecycle: SENT → DELIVERED → READ, receiver-driven

- A message is stored as `SENT`. Nothing transitions it on the sender's request.
- The receiver ACKs each received message over STOMP (`/app/message-ack`,
  `MessageService.acknowledgeDelivered`); the ACK is only honored for participants
  of the message's conversation, and the guarded `UPDATE ... WHERE status = SENT`
  prevents downgrades.
- Offline delivery: when the receiver loads the conversation history, all still-SENT
  messages sent to them transition to DELIVERED (one bulk `UPDATE`) and each affected
  sender gets a `DELIVERED` notification **without** a `messageId`, meaning "all of
  your messages in this conversation were delivered".

### Replies

- `SendMessageRequest.replyToMessageId` must exist and belong to the same
  conversation (otherwise the endpoint would leak message ids from conversations the
  sender is not part of). The reply payload carries `replyToContent`/`replyToType`
  previews; a reply to a deleted message shows the deleted placeholder.

### Edit

- Sender-only (`AccessDeniedException` otherwise), forbidden on deleted messages,
  sets `edited=true` + `editedAt`, and broadcasts `MESSAGE_EDITED` with the updated
  message so all open UIs update in place (including the conversation preview).

### Soft delete — never expose deleted content again

- `mode=me` sets `deleted_for_sender`; `mode=everyone` sets `deleted_for_everyone`.
  Both are sender-only. Nothing is physically removed (attachments stay for audit).
- `MessageMapper` is viewer-aware: a deleted message maps to the placeholder
  `"This message was deleted"` with empty attachments for the affected viewer(s)
  (for-me: only the sender; for-everyone: everyone).
- Deleted content is masked **everywhere**: history, WebSocket payloads, replies,
  and the conversation preview (`ConversationMapper` masks `lastMessage` the same
  way). The deleter's UI masks locally on the 204 — no WS echo is sent to the deleter.

## Consequences

- Ticks are truthful: SENT = accepted by server, DELIVERED = receiver acknowledged,
  READ = receiver opened the conversation.
- One `V2__message_features.sql` migration adds `reply_to_message_id`,
  `edited`, `edited_at`, `deleted_for_sender`, `deleted_for_everyone`
  (Flyway applies it to existing databases on boot; self-referencing FK is
  `ON DELETE SET NULL`).
- Editing and deletion are final once `deleted_for_everyone` is set; edits before
  deletion remain as the last stored content but are never rendered again.
