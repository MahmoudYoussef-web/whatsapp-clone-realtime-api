<div align="center">

# WhatsApp Clone — Realtime Messaging API

[![Typing SVG](https://readme-typing-svg.demolab.com?font=Fira+Code&weight=600&size=26&duration=3200&pause=600&color=25C16B&center=true&vCenter=true&width=760&height=70&lines=WhatsApp+Clone;A+backend+that+thinks+about+delivery;SENT+%E2%86%92+DELIVERED+%E2%86%92+READ%2C+guaranteed;Not+just+another+CRUD+API)](https://git.io/typing-svg)

Real-time messaging backend engineered for **correct delivery** — receiver-driven receipts, live presence, media that never touches the API layer.

</div>

## Demo

> 🎬 **Watch it in action** — [1-minute demo video](https://youtu.be/sQ1lk4SupzI)

<p align="center">
  <img src="docs/screenshots/chat-dark.png" width="800" alt="Chat UI — dark mode"/>
</p>
<p align="center">
  <img src="docs/screenshots/chat-light.png" width="800" alt="Chat UI — light mode"/>
</p>

<details>
<summary><b>More screenshots</b></summary>

| Group info & members | Status stories | Voice call |
|---|---|---|
| <img src="docs/screenshots/group-info.png" width="260" alt="Group info modal"/> | <img src="docs/screenshots/status.png" width="260" alt="Status stories"/> | <img src="docs/screenshots/voice-call.png" width="260" alt="Voice call overlay"/> |

</details>

## Badges

[![CI](https://github.com/MahmoudYoussef-web/whatsapp-clone-realtime-api/actions/workflows/build.yml/badge.svg)](https://github.com/MahmoudYoussef-web/whatsapp-clone-realtime-api/actions/workflows/build.yml)

[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.java.com/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.13-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-FF4438?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![OAuth2 · JWT](https://img.shields.io/badge/OAuth2%20%C2%B7%20JWT-Keycloak-000000?style=for-the-badge&logo=keycloak&logoColor=white)](https://www.keycloak.org/)
[![Flyway](https://img.shields.io/badge/Flyway-migrations-CC0200?style=for-the-badge&logo=flyway&logoColor=white)](https://flywaydb.org/)
[![Docker](https://img.shields.io/badge/Docker-compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)
[![Swagger](https://img.shields.io/badge/Swagger-OpenAPI-85EA2D?style=for-the-badge&logo=swagger&logoColor=white)](https://swagger.io/)
[![Maven](https://img.shields.io/badge/Maven-build-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![WebSocket](https://img.shields.io/badge/WebSocket-STOMP-010101?style=for-the-badge&logo=socketdotio&logoColor=white)](https://spring.io/projects/spring-websocket)

[![Architecture](https://img.shields.io/badge/%F0%9F%93%90-Architecture-25C16B?style=flat-square)](./README.md#system-architecture)
[![Design Decisions](https://img.shields.io/badge/%F0%9F%A7%A0-Design%20Decisions-25C16B?style=flat-square)](./README.md#overview)
[![Features](https://img.shields.io/badge/%F0%9F%93%A6-Features-25C16B?style=flat-square)](./README.md#features)
[![API Reference](https://img.shields.io/badge/%F0%9F%94%8C-API%20Reference-25C16B?style=flat-square)](./README.md#api-reference)
[![Database](https://img.shields.io/badge/%F0%9F%97%84-Database%20Schema-25C16B?style=flat-square)](./README.md#database-schema)
[![Security](https://img.shields.io/badge/%F0%9F%94%92-Security-25C16B?style=flat-square)](./README.md#security)
[![Getting Started](https://img.shields.io/badge/%F0%9F%9A%80-Getting%20Started-25C16B?style=flat-square)](./README.md#getting-started)

---

## Table of Contents

- [Demo](#demo)
- [Overview — Design decisions that go beyond a typical CRUD API](#overview)
- [System Architecture](#system-architecture)
- [Sequence Diagram — Message Lifecycle](#sequence-diagram--message-lifecycle)
- [Features](#features)
- [API Reference](#api-reference)
- [Database Schema](#database-schema)
- [Tech Stack](#tech-stack)
- [Security](#security)
- [Getting Started](#getting-started)
- [Testing](#testing)
- [Project Structure](#project-structure)
- [Author](#author)

---

## Overview

This is a portfolio-grade refactor of a WhatsApp-style clone. It is a **multi-process realtime system** — Spring Boot API + PostgreSQL + Redis + MinIO + Keycloak — not a CRUD wrapper. The interesting work lives in delivery semantics, realtime identity, and the boundaries between the layers.

### Design decisions that go beyond a typical CRUD API

| Challenge | How it's solved |
|---|---|
| **Media would either stream `byte[]` through the API or leak raw bucket paths** | Files go straight to MinIO; the API only ever returns **presigned GET URLs with a 15-minute TTL**. Raw bytes never cross REST/WS (ADR-0002). |
| **Realtime identity can be spoofed from the payload** | The STOMP identity is always the authenticated **session token** attached by a channel interceptor — `@AuthenticationPrincipal` never reads user IDs from message bodies (ADR-0011). |
| **SockJS cannot send custom headers on the HTTP handshake** | The JWT travels as `Authorization: Bearer` on the **CONNECT frame**; a channel interceptor validates it, checks the subject exists locally, and re-validates `exp` on **every frame** (ADR-0011, ADR-0012, ADR-0013). |
| **Unread counters are a classic read-modify-write race** | `unread_count` is mutated by **single atomic SQL UPDATEs** — never loaded-then-saved in Java. Race-safe by construction (ADR-0001). |
| **Bulk status updates would silently drop dirty entities** | Bulk queries run with `clearAutomatically=true`; the conversation preview is pinned via an **explicit UPDATE** so it can never be lost (ADR-0005). |
| **Offline users never receive their messages' receipts** | Delivery is **receiver-driven**: the receiver ACKs over STOMP; when a viewer loads history, all `SENT` messages sent to them transition to `DELIVERED` and each sender is notified (ADR-0010). |
| **Status transitions could downgrade (READ → DELIVERED)** | Every transition is a **guarded conditional UPDATE** (`WHERE status = expected`) — a receipt can never move backwards. |
| **Every request would hit the DB just to sync users** | User sync from the JWT is **throttled per user to once per 60 s** via a Caffeine cache — bounded DB writes under load (ADR-0006). |
| **Message history pages would suffer N+1 on attachments** | Attachments for a whole page are fetched in **one query** and grouped in memory. |
| **New messages arriving during pagination shift offsets** | **Cursor pagination** on the monotonic message id (`before` + `limit`, `hasMore` detection) — stable pages, no offsets (ADR-0003). |
| **Hibernate and the schema would drift apart** | **Flyway owns the schema**; Hibernate runs `ddl-auto: validate` and refuses to boot on mismatch (ADR-0004). |
| **Redis going down would take the app with it** | Presence calls **degrade gracefully** — the app stays up and simply reports everyone offline. |
| **Bulk deletes or masked content leaking into previews** | Deleted messages are masked **per viewer** at mapping time — content, attachments and previews never expose them again. |

---

## System Architecture

```mermaid
graph TD
    subgraph Client["Client — Angular 19 (localhost:4200)"]
        UI[Angular SPA]
        STOMP[SockJS + STOMP client]
        KEYCLOAKJS["Keycloak JS (OIDC login)"]
    end

    subgraph IdP["Identity Provider"]
        KC[Keycloak 26]
    end

    subgraph Backend["Spring Boot 3.4.13 (8080)"]
        subgraph SecurityLayer["Security Layer"]
            CORS[CORS Filter<br/>localhost:4200 only]
            JWT[OAuth2 Resource Server<br/>KeycloakJwtAuthenticationConverter]
            SYNC["UserSynchronizerFilter<br/>JWT → users upsert (Caffeine-throttled)"]
            WSINTERCEPT[UserPresenceChannelInterceptor<br/>CONNECT auth · per-frame exp · presence]
            SECTX[SecurityContextChannelInterceptor<br/>STOMP identity → SecurityContextHolder]
        end

        subgraph BusinessLayer["Business Layer"]
            CONVC[ConversationController<br/>private + groups + pin/archive]
            MSGC[MessageController<br/>send/media/forward/search/info]
            USERC[UserController<br/>+ avatar upload]
            STATUSC[StatusController<br/>24h stories]
            REALTIME[RealtimeController<br/>/app/typing · /app/message-ack · /app/call-signal]
        end

        subgraph Services["Services"]
            CONSVC[ConversationService]
            MSSVC[MessageService]
            TYPSVC[TypingService]
            CALLSVC[CallService<br/>WebRTC signaling relay]
            STATUSSVC[StatusService<br/>+ hourly expiry sweep]
            NOTIF[NotificationService]
            PRES[PresenceService]
            STORAGE[FileStorageService<br/>+ image thumbnails]
            RATELIMIT[RateLimitFilter<br/>60 mutating req/min]
        end
    end

    subgraph Data["Data & Infrastructure"]
        PG[(PostgreSQL 16)]
        REDIS[(Redis 7)]
        MINIO[(MinIO — S3 compatible)]
        TURN[(coturn — TURN relay)]
    end

    UI -->|REST · Bearer JWT| CORS
    UI -->|STOMP over WS /ws| WSINTERCEPT
    CORS --> JWT --> RATELIMIT --> SYNC
    SYNC --> CONVC
    SYNC --> MSGC
    SYNC --> USERC
    SYNC --> STATUSC
    WSINTERCEPT --> SECTX --> REALTIME
    CONVC --> CONSVC
    MSGC --> MSSVC
    STATUSC --> STATUSSVC
    REALTIME --> TYPSVC
    REALTIME --> MSSVC
    REALTIME --> CALLSVC
    MSSVC --> NOTIF
    MSSVC --> STORAGE
    STATUSSVC --> STORAGE
    TYPSVC --> NOTIF
    CALLSVC --> NOTIF
    CONSVC --> PG
    MSSVC --> PG
    STATUSSVC --> PG
    PRES --> REDIS
    STORAGE --> MINIO
    NOTIF -->|"/user/{id}/chat"| STOMP
    UI -->|OIDC authorize| KC
    KC -->|JWT · issuer| JWT
    MINIO -->|presigned GET URLs| UI
    STOMP -->|P2P audio via STUN/TURN| TURN
```

---

## Sequence Diagram — Message Lifecycle

`SENT → DELIVERED → READ`, with both the live path (WebSocket ACK) and the offline path (history load):

```mermaid
sequenceDiagram
    autonumber
    actor Sender
    actor Receiver
    participant API as Spring Boot API
    participant DB as PostgreSQL
    participant WS as STOMP Broker
    participant S3 as MinIO

    Sender->>API: POST /conversations/{id}/messages (Bearer JWT)
    API->>API: requireParticipant(conversationId, senderId)
    API->>DB: INSERT message (status=SENT)
    API->>DB: atomic UPDATE unread_count + last_message_id
    API->>WS: MESSAGE notification → /user/{receiverId}/chat
    WS-->>Receiver: MESSAGE (messageId, content, presigned URLs)

    Receiver->>WS: /app/message-ack {messageId}
    WS->>API: acknowledgeDelivered(messageId)
    API->>DB: guarded UPDATE SENT → DELIVERED
    API->>WS: DELIVERED notification → /user/{senderId}/chat
    WS-->>Sender: DELIVERED (ticks: 1 check)

    Receiver->>API: PATCH /conversations/{id}/read
    API->>DB: UPDATE ... SET status=READ WHERE sender != viewer
    API->>DB: reset unread_count
    API->>WS: READ notification → /user/{senderId}/chat
    WS-->>Sender: READ (ticks: 2 checks)

    Note over Receiver,DB: Offline path: no ACK while away
    Receiver->>API: GET /messages (loads history)
    API->>DB: bulk SENT → DELIVERED (receiver-driven)
    API->>WS: DELIVERED (no messageId = all messages) → sender
    WS-->>Sender: DELIVERED
```

---

## Features

### 🔐 Auth & Security
- **Keycloak 26 (OIDC)** as the resource server issuer — no local password storage, no signup logic in the API
- **JWT authorities** from both `scope` and Keycloak's `resource_access` claim — defensive against missing/oddly-typed claims (no NPE/ClassCastException)
- **STOMP CONNECT authentication** — Bearer token on the CONNECT frame, local-account existence check before the session is accepted (no anonymous sessions)
- **Per-frame token expiry gate** — an expired token kills the session mid-stream, same clock-skew semantics as CONNECT (ADR-0013)
- **Identity from session, never from payload** — spoofing another user over WebSocket is impossible by construction
- **Participant guard** on every conversation operation — non-members get 404, not data
- **Sender-only** edit/delete, reply targets validated to stay in-conversation

### 💬 Core Messaging
- **Text messages** with optional **reply-to** (validated to the same conversation, masked if the parent was deleted)
- **Attachments** — images, video, audio, documents via MinIO, delivered as **presigned URLs** (15 min TTL); images get **320px thumbnails** so the chat list never downloads originals
- **Message lifecycle `SENT → DELIVERED → READ`** with guarded, never-downgrading transitions
- **Receiver-driven delivery receipts** — live ACK over WebSocket + **offline delivery** on history load
- **Cursor pagination** for history (`before` + `limit`, `hasMore`, max 100/page) with zero N+1 on attachments
- **Edit** (`edited` / `editedAt`) and **soft-delete** with two modes: `me` (hides for the deleter) and `everyone` (masks content + attachments for all, including previews)
- **Idempotent private conversation creation** — no duplicate chats between the same two users; self-chats rejected
- **Unread counters** per participant (atomic updates) + **last-message preview** pinned on every send
- **Group chats** — create/rename, admin-managed members (add/remove), leave, group avatar, member roster with roles
- **Voice notes** — in-browser recording (MediaRecorder) sent as audio attachments with inline playback
- **Forward + message info** — forwarded badge, forward-to-any-chat, per-message read approximation ("read by…")
- **In-conversation search** (trigram index) with click-to-jump + highlight
- **Pin / archive** per participant (pinned sorts first), unread divider, per-user avatar uploads

### 💬 Status & Calls
- **Status stories (24h)** — text/photo/video, per-user feed, hourly expiry sweep (DB + MinIO cleanup)
- **1:1 voice calls (WebRTC)** — signaling relayed over STOMP (`CALL_OFFER/ANSWER/ICE/END`), P2P audio via STUN/coturn; nothing recorded server-side

### 🌍 Frontend
- **Angular 19** — dark/light theme, **Arabic/English + RTL**, PWA installable (Angular service worker), Smart list dates + read ticks, fullscreen media viewer with zoom

### ⚡ Realtime & Presence
- **Live typing indicators** (`/app/typing`) relayed only to other participants
- **WebSocket notifications** on `/user/{id}/chat` — `MESSAGE`, `DELIVERED`, `READ`, `TYPING`, `MESSAGE_EDITED`, `MESSAGE_DELETED`, `REACTION_*`, `MEMBER_ADDED/REMOVED`, `GROUP_INFO_UPDATED`, `CALL_OFFER/ANSWER/ICE/END`
- **Presence via Redis** — `presence:user:{id}` key with a 60 s sliding TTL, refreshed on every STOMP frame, cleaned on DISCONNECT; **graceful degradation** if Redis is unreachable
- **Online status** surfaced in the user list and conversation list (with `lastSeen` fallback)

### 🗄️ Data & Integrity
- **Flyway-owned schema** (V1–V10: conversations → reactions → groups → forward → search → avatars → status → thumbnails) with `ddl-auto: validate` boot-time contract check
- **Composite index `(conversation_id, id)`** for cursor pagination; participant/attachment indexes; **trigram index** for message search
- **Atomic counters and guarded status transitions** — no read-modify-write races anywhere in the hot path
- **Caffeine-throttled user sync** — each user synced from the IdP at most once per 60 s

### 📊 Observability & DX
- **SpringDoc OpenAPI / Swagger UI** — live API docs at `/swagger-ui/index.html`
- **Spring Boot Actuator** health/metrics endpoints (+ `whatsapp.messages.sent` counter by kind)
- **Rate limiting** — 60 mutating requests/min per user (RFC 7807 `429`), dependency-free fixed window
- **GitHub Actions CI** — backend `mvn verify` (incl. Testcontainers) + frontend build + compose validation on every push/PR
- **RFC 7807 ProblemDetail** error responses with stable HTTP semantics (404/400/422-style validation, generic storage errors without internal leaks)
- **84 tests, 0 failures** — 77 unit tests (Mockito) + 7 Testcontainers integration tests against real PostgreSQL 16
- **13 Architecture Decision Records** documenting every non-trivial choice in `docs/decisions/`

> 🤖 **No AI features** in this project — no LLM integration, no content intelligence. Not a gap, a deliberate scope decision.

---

## API Reference

Base URL: `http://localhost:8080` — every endpoint requires `Authorization: Bearer <keycloak-jwt>` (except `/ws/**` which authenticates on the STOMP CONNECT frame).

### Core endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/users` | All users except the caller, with live online status |
| `POST` | `/api/v1/conversations` | Create a private conversation (idempotent) — body `{ "participantId": "..." }` |
| `GET` | `/api/v1/conversations` | Conversation list: preview, unread count, presence, last seen |
| `GET` | `/api/v1/conversations/{conversationId}/messages?before&limit` | Cursor-paginated history, newest first |
| `POST` | `/api/v1/conversations/{conversationId}/messages` | Send a text message (optional `replyToMessageId`) |
| `POST` | `/api/v1/conversations/{conversationId}/messages/attachments` | Upload media (`multipart/form-data` file) |
| `PATCH` | `/api/v1/conversations/{conversationId}/read` | Mark received messages as READ + reset unread counter |
| `PATCH` | `/api/v1/conversations/{conversationId}/messages/{messageId}` | Edit a message (sender only) |
| `DELETE` | `/api/v1/conversations/{conversationId}/messages/{messageId}?mode=me\|everyone` | Soft-delete a message (sender only) |
| `POST` | `/api/v1/conversations/groups` | Create a group — body `{ "name": "...", "memberIds": [...] }` (creator becomes admin) |
| `GET` | `/api/v1/conversations/{conversationId}/members` | Group roster: roles, avatars, self flag |
| `POST` | `/api/v1/conversations/{conversationId}/members` | Add a member (admin only) |
| `DELETE` | `/api/v1/conversations/{conversationId}/members/{userId}` | Remove a member (admin only) |
| `POST` | `/api/v1/conversations/{conversationId}/leave` | Leave a group |
| `PATCH` | `/api/v1/conversations/{conversationId}/name` | Rename a group (admin only) |
| `POST` | `/api/v1/conversations/{conversationId}/avatar` | Upload group avatar (admin only) |
| `PATCH` | `/api/v1/conversations/{conversationId}/pin` | Pin/unpin chat — body `{ "value": true }` |
| `PATCH` | `/api/v1/conversations/{conversationId}/archive` | Archive/unarchive chat |
| `POST` | `/api/v1/conversations/{conversationId}/messages/{messageId}/forward` | Forward to another conversation — body `{ "targetConversationId": "..." }` |
| `GET` | `/api/v1/conversations/{conversationId}/messages/{messageId}/info` | Message info: per-participant read approximation |
| `GET` | `/api/v1/conversations/{conversationId}/messages/search?q=...` | In-conversation text search (max 20 hits) |
| `POST` | `/api/v1/conversations/{conversationId}/messages/{messageId}/reactions` | Add/update reaction — body `{ "emoji": "👍" }` |
| `DELETE` | `/api/v1/conversations/{conversationId}/messages/{messageId}/reactions` | Remove own reaction |
| `GET` | `/api/v1/users/me` | Own profile (with avatar URL) |
| `POST` | `/api/v1/users/me/avatar` | Upload profile avatar (`multipart/form-data`) |
| `POST` | `/api/v1/status` | Post a text status (24h TTL) |
| `POST` | `/api/v1/status/media` | Post a photo/video status |
| `GET` | `/api/v1/status/feed` | Status feed grouped per user (unexpired only) |
| `DELETE` | `/api/v1/status/{statusId}` | Delete own status |

Call signaling travels over STOMP (`/app/call-signal` with `{ conversationId, targetUserId, signal, payload }`) — see the WebSocket table below.

<details>
<summary><b>Example — send a message</b></summary>

**Request**

```http
POST /api/v1/conversations/0c5c9b1e-3f3f-4a0e-8f7c-1d0e2f3a4b5c/messages
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{
  "type": "TEXT",
  "content": "Hey, delivered yet?",
  "replyToMessageId": 42
}
```

**Response — `201 Created`**

```json
{
  "id": 43,
  "content": "Hey, delivered yet?",
  "type": "TEXT",
  "status": "SENT",
  "senderId": "9f2b6c4e-...",
  "createdAt": "2026-08-04T12:30:15",
  "attachments": [],
  "replyToMessageId": 42,
  "replyToContent": "Nice to see you!",
  "replyToType": "TEXT",
  "edited": false,
  "editedAt": null,
  "deleted": false
}
```

</details>

<details>
<summary><b>Example — upload an attachment</b></summary>

**Request**

```http
POST /api/v1/conversations/0c5c9b1e-3f3f-4a0e-8f7c-1d0e2f3a4b5c/messages/attachments
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: multipart/form-data

file: photo.jpg (binary)
```

**Response — `201 Created`**

```json
{
  "id": 44,
  "content": null,
  "type": "IMAGE",
  "status": "SENT",
  "senderId": "9f2b6c4e-...",
  "createdAt": "2026-08-04T12:31:02",
  "attachments": [
    {
      "id": 10,
      "objectKey": "users/9f2b6c4e/0c5c9b1e/d41d8cd9-1234-5678-9abc-def012345678.jpg",
      "mimeType": "image/jpeg",
      "sizeBytes": 2451671,
      "width": null,
      "height": null,
      "durationSeconds": null,
      "url": "http://localhost:9000/whatsapp-media/users/9f2b6c4e/...?X-Amz-Signature=..."
    }
  ],
  "replyToMessageId": null,
  "replyToContent": null,
  "replyToType": null,
  "edited": false,
  "editedAt": null,
  "deleted": false
}
```

</details>

<details>
<summary><b>WebSocket / STOMP surface</b></summary>

| Direction | Destination | Payload | Purpose |
|---|---|---|---|
| Subscribe | `/user/{sub}/chat` | — | Notification queue (types: `MESSAGE`, `DELIVERED`, `READ`, `TYPING`, `MESSAGE_EDITED`, `MESSAGE_DELETED`) |
| Send | `/app/typing` | `{ "conversationId": "...", "typing": true }` | Typing indicator (relayed to other participants only) |
| Send | `/app/message-ack` | `{ "messageId": 43 }` | Delivery receipt: `SENT → DELIVERED` |
| Send | `/app/call-signal` | `{ "conversationId": "...", "targetUserId": "...", "signal": "CALL_OFFER", "payload": "<SDP/ICE JSON>" }` | 1:1 voice-call signaling (signals: `CALL_OFFER/ANSWER/ICE/END`; audio is P2P via STUN/coturn) |

**Connection requirements**

- Endpoint `/ws` with SockJS
- The CONNECT frame must carry `Authorization: Bearer <jwt>`
- A `DELIVERED` notification **without** `messageId` means "everything you sent in this conversation was delivered" (offline delivery on history load)
- Notifications never carry media bytes — attachments travel as presigned URLs inside `MessageResponse`

</details>

<details>
<summary><b>Error responses (RFC 7807 ProblemDetail)</b></summary>

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "User 9f2b6c4e is not a participant of conversation 0c5c9b1e"
}
```

Validation failures return a `400 Validation Failed` problem with `field: message` pairs; storage failures return a generic `500 File storage failure` (internal details never leak).

</details>

---

## Database Schema

```mermaid
erDiagram
    USERS ||--o{ CONVERSATION_PARTICIPANTS : joins
    CONVERSATIONS ||--o{ CONVERSATION_PARTICIPANTS : contains
    CONVERSATIONS ||--o{ MESSAGES : contains
    MESSAGES ||--o{ ATTACHMENTS : has
    MESSAGES |o--o| MESSAGES : "reply_to_message_id"
    CONVERSATIONS o|--o| MESSAGES : "last_message_id"
    CONVERSATION_PARTICIPANTS o|--o| MESSAGES : "last_read_message_id"

    USERS {
        varchar id PK "Keycloak subject"
        varchar first_name
        varchar last_name
        varchar email
        timestamp last_seen
    }
    CONVERSATIONS {
        uuid id PK
        varchar type "PRIVATE (multi-party ready)"
        bigint last_message_id FK
    }
    CONVERSATION_PARTICIPANTS {
        bigint id PK
        uuid conversation_id FK
        varchar user_id FK
        varchar role "MEMBER"
        timestamp joined_at
        bigint last_read_message_id FK
        int unread_count "atomic UPDATEs"
    }
    MESSAGES {
        bigint id PK "identity · cursor for pagination"
        uuid conversation_id FK
        varchar sender_id FK
        varchar type "TEXT/IMAGE/VIDEO/AUDIO/FILE"
        text content
        varchar status "SENT/DELIVERED/READ"
        bigint reply_to_message_id FK
        boolean edited
        timestamp edited_at
        boolean deleted_for_sender
        boolean deleted_for_everyone
    }
    ATTACHMENTS {
        bigint id PK
        bigint message_id FK
        varchar object_key "users/{userId}/{conversationId}/{uuid}.ext"
        varchar bucket
        varchar mime_type
        bigint size_bytes
    }
```

Owned by Flyway migrations (`V1`–`V10`: conversations, message features, reactions, deletions, groups, forward flag, trigram search, avatars/pin/archive, statuses, thumbnails); Hibernate validates against it at boot (`ddl-auto: validate`). Key indexes: `(conversation_id, id)` for cursor pagination, `conversation_participants(user_id)` for the conversation list, `attachments(message_id)`, trigram index on message content.

> Full visual ERD: `resources/erd.png` — the same model rendered with actual cardinalities.

---

## Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| Runtime | Java 17 · Spring Boot 3.4.13 | Maven build, Lombok |
| Web | Spring Web MVC | JSON via Jackson (jsr310 for WebSocket converters, ADR-0007) |
| Security | Spring Security OAuth2 Resource Server | Keycloak 26 as JWT issuer |
| Realtime | Spring WebSocket + STOMP + SockJS | Simple broker on `/user`, `spring-security-messaging` for the context bridge |
| Persistence | Spring Data JPA (Hibernate) | `ddl-auto: validate` only — Flyway owns the schema |
| Migrations | Flyway | `V1`–`V10` in `classpath:db/migration` |
| Database | PostgreSQL 16 | via `docker-compose.yml` |
| Cache | Redis 7 (presence) · Caffeine (user-sync throttle) | Redis TTL keys `presence:user:{id}` |
| Object storage | MinIO via AWS SDK v2 S3 | Presigned GET URLs, 15 min TTL |
| API docs | SpringDoc OpenAPI 2.8.17 | Swagger UI + generated client (`ng-openapi-gen`) |
| Ops | Spring Boot Actuator | Health/metrics |
| Tests | JUnit 5 · Mockito · Testcontainers | 84 tests: 77 unit + 7 integration (real PostgreSQL) |
| Deployment | Docker Compose | postgres:16-alpine · keycloak:26.0.0 · minio · redis:7-alpine · coturn |
| Frontend | Angular 19 + PWA | Dark/light, ar/en + RTL, service-worker offline shell |
| CI | GitHub Actions | Backend verify + frontend build + compose check |

**Honest dependency audit:** every dependency declared in `pom.xml` is actually wired and exercised — there is no dead weight (no resilience/queue libraries like Resilience4j or Quartz are declared; Caffeine is used directly by `UserSynchronizer`).

---

## Security

Protection is layered — REST, WebSocket, and data access each have their own enforcement:

1. **REST layer** — OAuth2 Resource Server validates the JWT signature/issuer/expiry against Keycloak; `KeycloakJwtAuthenticationConverter` maps `scope` + `resource_access` claims to authorities (defensive against malformed claims).
2. **User sync filter** — runs *after* successful bearer auth (never on failed auth — a cleared context can't NPE), throttled per user by Caffeine to bound DB writes.
3. **WebSocket layer** — `UserPresenceChannelInterceptor` authenticates the CONNECT frame, rejects subjects not present in the local users table, re-validates `exp` on every frame, and `SecurityContextChannelInterceptor` bridges the STOMP identity into `SecurityContextHolder` so `@AuthenticationPrincipal` works in message handlers.
4. **Authorization at the data layer** — `requireParticipant()` gates every conversation operation; edit/delete require sender identity; reply targets must live in the same conversation; deleted content is masked per viewer at mapping time.
5. **Receipt integrity** — status transitions are guarded `UPDATE ... WHERE status = expected`; a message can never be downgraded or double-acked into a different state.
6. **Media hardening** — extension whitelist → MIME mapping, per-category size caps (image 10 MB · audio 25 MB · video 100 MB · document 25 MB), files stored under `users/{userId}/...`, access only via short-lived presigned URLs.
7. **Error hygiene** — `ProblemDetail` responses; storage failures return a generic message, no stack traces or internal state leaked.

### Known Limitations (honest)

| Limitation | Impact | Status |
|---|---|---|
| CSRF disabled (`csrf.disable()`) + CORS from env (`APP_CORS_ALLOWED_ORIGINS`, default `http://localhost:4200`) | Dev-stage posture; fine for a portfolio/demo, must be revisited before any real deployment | Deliberate for dev |
| Read receipts are **conversation-wide**, not per-message | Opening a chat marks all received messages READ; message info is an approximation | Phase scope (ADR-0010) |
| Presence = **one WebSocket connection per user** (last connection wins) | A second tab kicks the first one offline | Documented in root README |
| Voice calls are **1:1 audio only** (spike) | No video, no group calls; no ringing persistence when callee is offline | Spike scope |
| Status feed covers **all users** (no contacts model) | Anyone's story is visible to anyone authenticated | Simplification |
| **No end-to-end encryption** | Server stores plaintext content; media in MinIO is unencrypted at rest | Out of scope |
| **No multi-device sync** | Delivery/read state is single-device semantics | Out of scope |
| **No media upload progress / resumable uploads** | Single multipart upload, 100 MB request cap | Out of scope |
| Notifications are **ephemeral** (WS-only, not persisted) | Typing/live events missed while disconnected are lost — message history is the source of truth | By design |

---

## Getting Started

### Prerequisites

- Java 17+ and Maven 3.9+
- Docker (for infrastructure and integration tests)

### 1. Quick start (infrastructure via Docker Compose)

```bash
docker compose up -d
```

Starts PostgreSQL 16 (5432), Keycloak 26 (9090, realm auto-imported from `keycloak/realm.json` with demo users alice/bob), MinIO (9000/9001, bucket auto-created), Redis 7 (6379) and coturn (3478) on the `whatsapp-clone` network. Copy `.env.example` to `.env` to override ports/credentials.

### 2. Provision Keycloak (only if you skip the auto-import)

```bash
# 1. Open http://localhost:9090 — login admin / admin
# 2. Create realm: whatsapp-clone
# 3. Create client: whatsapp-clone-app (public, redirect http://localhost:4200/*)
# 4. Add http://localhost:8080 to the client's Web Origins
#    (needed only if testing via Swagger UI directly — not required for the Angular frontend flow)
# 5. Create users (alice, bob) with passwords
# 6. Log each user in once via the frontend so the backend syncs them locally
```

### 3. Start the backend

```bash
cd whatsappclone
mvn spring-boot:run
```

Flyway applies `V1`–`V10` on startup; the app boots only if the schema matches the entities (`ddl-auto: validate`). API: `http://localhost:8080`, Swagger UI: `http://localhost:8080/swagger-ui/index.html`.

### 4. Start the frontend (optional, Angular 19 PWA)

```bash
cd whatsapp-clone-ui
npm install
npm start         # http://localhost:4200 (dev)
npm run build     # production + service worker to dist/
```

---

## Testing

```bash
cd whatsappclone
mvn verify
```

**84 tests: 77 unit (JUnit 5 · Mockito) + 7 integration (Testcontainers)** against a real PostgreSQL 16 — no mocks for the schema. Integration tests spin up PostgreSQL via Testcontainers (auto-skipped without Docker). Docker Desktop on engine 29+ is pinned to API 1.44 via `src/test/resources/docker-java.properties` (ADR-0008).

---

## Project Structure

```
whatsapp-clone-main/
├── whatsappclone/                 # Spring Boot backend
│   └── src/main/java/com/alibou/whatsappclone/
│       ├── common/                # BaseAuditingEntity, StringResponse
│       ├── conversation/          # Private + group chats, pin/archive, member roster
│       ├── message/               # Lifecycle, forward/search/info, thumbnails, reactions
│       ├── notification/          # WebSocket notification payloads + publisher
│       ├── presence/              # Redis presence + STOMP channel interceptor
│       ├── security/              # JWT authorities converter, split SecurityFilterChains
│       ├── storage/               # MinIO, whitelist validator, presigned URLs, avatars
│       ├── user/                  # User entity + IdP sync (Caffeine-throttled) + avatar
│       ├── status/                # 24h stories + hourly expiry sweep
│       ├── ratelimit/             # Fixed-window 429 filter (no extra deps)
│       ├── ws/                    # WebSocketConfig, RealtimeController, TypingService, CallService
│       ├── interceptor/           # UserSynchronizerFilter
│       └── exception/             # GlobalExceptionHandler (ProblemDetail)
├── whatsapp-clone-ui/             # Angular 19 PWA (SockJS/STOMP, Keycloak, ar/en + RTL)
│   └── src/app/
│       ├── components/            # chat-list, message-bubble, media-viewer, group-info, status-strip
│       ├── pages/main/            # Chat shell: composer, voice notes, calls, search, modals
│       ├── services/              # Hand-written group/status/call/profile/message extras
│       └── i18n/                  # en + ar dictionaries, dir handling
├── .github/workflows/             # CI: backend verify + frontend build
├── docs/decisions/                # 13 ADRs (0001–0013)
├── docs/screenshots/              # UI captures (see Demo)
├── resources/                     # ERD diagram, design assets
└── docker-compose.yml             # postgres, keycloak, minio, redis, coturn
```

---

## Author

<table>
  <tr>
    <td align="center" width="300">
      <b>Mahmoud Youssef</b><br/>
      <sub>Backend Engineer</sub><br/><br/>
      <a href="https://github.com/MahmoudYoussef-web">
        <img src="https://img.shields.io/badge/GitHub-MahmoudYoussef--web-181717?style=flat-square&logo=github"/>
      </a>
      <br/>
      <a href="https://www.linkedin.com/in/mahmoud-youssef-ba30723bb">
        <img src="https://img.shields.io/badge/LinkedIn-mahmoud--youssef-0A66C2?style=flat-square&logo=linkedin&logoColor=white"/>
      </a>
    </td>
  </tr>
</table>

---

<div align="center">
  <sub>Licensed under the Apache License 2.0 · Built with Spring Boot, PostgreSQL, Redis, MinIO & Keycloak</sub>
</div>
