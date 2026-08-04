# ADR-0005: Bulk-update persistence-context clearing vs conversation preview

**Status:** Accepted · **Phase:** 1 · **Date:** 2026-08-02

## Context

Symptom (found by end-to-end smoke testing, not unit tests): the conversation list returned
`lastMessage: null` forever. Root cause: `sendTextMessage` mutated the managed
`Conversation` (`conversation.setLastMessage(message)`) and then ran sibling bulk updates
(unread counter, message status). Spring Data `@Modifying` queries marked
`clearAutomatically = true` wipe the persistence context **after** execution, silently
detaching the dirty `Conversation`. Hibernate does not auto-flush before bulk
UPDATE/DELETE statements, so at commit there was nothing left to flush — the
`last_message_id` write was lost.

## Decision

- Never express cross-cutting preview updates as entity mutation + save when bulk updates
  run in the same transaction.
- `ConversationRepository.updateLastMessage(conversationId, messageId)` is itself an
  explicit `@Modifying` UPDATE — immune to persistence-context clearing and flush ordering.
- `markLastMessage` is now a direct SQL statement.

## Consequences

- Conversation previews are always pinned to the newest message.
- Regression guard added to the Testcontainers suite
  (`cursorPaginationReturnsNewestFirstWithHasMore` asserts `lastMessageId` after sends).
