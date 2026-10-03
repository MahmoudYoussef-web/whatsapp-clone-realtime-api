# ADR-0009: Redis presence and the WebSocket channel interceptor

**Status:** Accepted · **Phase:** 2 · **Date:** 2026-08-02

## Context

Phase 1 reported online status from the stale `user.user_online` DB column. Phase 2
needs live presence ("online" = active WebSocket connection) without polling and
without a broker-relay upgrade. Requirements: connections disappear silently (browser
killed, network drop) so presence must expire by itself; a second tab/device must not
permanently break status.

## Decision

- Presence is a Redis key `presence:user:{id}` holding `1` with a sliding TTL
  (60 s, `application.presence.redis-ttl-seconds`).
- A STOMP channel interceptor (`UserPresenceChannelInterceptor`) refreshes it:
  - `CONNECT` → `setOnline` (key + TTL) and store the user id in the session attributes,
  - `DISCONNECT` → `markOffline` (key deleted),
  - any other frame → TTL refresh (sliding window; an idle-but-connected client is
    still online because the simple broker's heartbeats count as frames).
- The interceptor is ordered `LOWEST_PRECEDENCE`, so it runs after any other
  ordered interceptors on the channel. (Correction, ADR-0011: spring-security-messaging
  has no CONNECT-frame authentication interceptor in Spring Security 6.4, so the
  interceptor itself validates the Bearer token and establishes the session
  identity; the `SecurityContextChannelInterceptor` bridge is registered after
  it in `WebSocketConfig`.)
- `PresenceService` swallows Redis failures (logs a warning, reports offline) — Redis
  being down never breaks messaging, only presence accuracy.

## Consequences

- `ConversationResponse.otherUserOnline` and `UserResponse.online` are now live.
- Presence keys expire on crash/kill within the TTL (default 60 s).
- Multiple connections from one user share one key; the last frame writer wins —
  documented as a limitation (multi-device support is out of scope).
- No extra dependency: Redis was already in the stack; Lettuce keeps one pool.

## Alternatives considered

- `SessionConnectedEvent`/`SessionDisconnectEvent` listeners — must be enabled with
  `spring.messaging.simp.session...` and cannot catch every frame for the sliding TTL;
  the channel interceptor sees every frame without configuration.
- In-memory map — loses presence on restart and does not scale; Redis already exists.
