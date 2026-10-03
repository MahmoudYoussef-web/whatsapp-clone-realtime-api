# API Reference

Base URL: `http://localhost:8080` — every endpoint needs `Authorization: Bearer <keycloak-jwt>` (except `/ws/**` which sends the token on the STOMP CONNECT frame).

## Core endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/users` | All users except me, with online status |
| `POST` | `/api/v1/conversations` | New private chat, body `{ "participantId": "..." }` |
| `GET` | `/api/v1/conversations` | My chats: preview, unread count, presence |
| `GET` | `/api/v1/conversations/{id}/messages?before&limit` | Chat history, newest first |
| `POST` | `/api/v1/conversations/{id}/messages` | Send text (optional `replyToMessageId`) |
| `POST` | `/api/v1/conversations/{id}/messages/attachments` | Upload media (`multipart/form-data`) |
| `PATCH` | `/api/v1/conversations/{id}/read` | Mark as read + reset unread counter |
| `PATCH` | `/api/v1/conversations/{id}/messages/{msgId}` | Edit message (sender only, 15 min) |
| `DELETE` | `/api/v1/conversations/{id}/messages/{msgId}?mode=me\|everyone` | Delete message |
| `POST` | `/api/v1/conversations/groups` | New group, body `{ "name": "...", "memberIds": [...] }` |
| `GET` | `/api/v1/conversations/{id}/members` | Group members |
| `POST` | `/api/v1/conversations/{id}/members` | Add member (admin only) |
| `DELETE` | `/api/v1/conversations/{id}/members/{userId}` | Remove member (admin only) |
| `POST` | `/api/v1/conversations/{id}/leave` | Leave group |
| `PATCH` | `/api/v1/conversations/{id}/name` | Rename group (admin only) |
| `POST` | `/api/v1/conversations/{id}/avatar` | Group avatar (admin only) |
| `PATCH` | `/api/v1/conversations/{id}/pin` | Pin/unpin, body `{ "value": true }` |
| `PATCH` | `/api/v1/conversations/{id}/archive` | Archive/unarchive |
| `POST` | `/api/v1/conversations/{id}/messages/{msgId}/forward` | Forward, body `{ "targetConversationId": "..." }` |
| `GET` | `/api/v1/conversations/{id}/messages/{msgId}/info` | Who read it (approx) |
| `GET` | `/api/v1/conversations/{id}/messages/search?q=...` | Search in chat (max 20) |
| `POST` | `/api/v1/conversations/{id}/messages/{msgId}/reactions` | Add reaction, body `{ "emoji": "👍" }` |
| `DELETE` | `/api/v1/conversations/{id}/messages/{msgId}/reactions` | Remove my reaction |
| `GET` | `/api/v1/users/me` | My profile |
| `POST` | `/api/v1/users/me/avatar` | Upload avatar |
| `POST` | `/api/v1/status` | Post text status (24h) |
| `POST` | `/api/v1/status/media` | Post photo/video status |
| `GET` | `/api/v1/status/feed` | Status feed |
| `DELETE` | `/api/v1/status/{statusId}` | Delete my status |

## WebSocket / STOMP

| Direction | Destination | Payload | Purpose |
|---|---|---|---|
| Subscribe | `/user/{sub}/chat` | — | Notifications: `MESSAGE`, `DELIVERED`, `READ`, `TYPING`, `MESSAGE_EDITED`, `MESSAGE_DELETED` |
| Send | `/app/typing` | `{ "conversationId": "...", "typing": true }` | Typing indicator |
| Send | `/app/message-ack` | `{ "messageId": 43 }` | Delivery receipt |
| Send | `/app/call-signal` | `{ "conversationId": "...", "targetUserId": "...", "signal": "...", "payload": "..." }` | Call signaling |

Connect to `/ws` with SockJS, send `Authorization: Bearer <jwt>` on the CONNECT frame.

## Errors

RFC 7807 `ProblemDetail`. Example 404:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "not a participant"
}
```
