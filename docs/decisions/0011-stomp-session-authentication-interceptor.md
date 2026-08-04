# ADR-0011: STOMP session authentication via the channel interceptor

**Status:** Accepted · **Phase:** 2 · **Date:** 2026-08-02

## Context

`@MessageMapping("/typing")` and `@MessageMapping("/message-ack")` must resolve the
caller's identity so spoofing another user (e.g. ACKing someone else's message) is
impossible. The browser connects over SockJS + STOMP and passes the Keycloak JWT as
an `Authorization` header on the STOMP CONNECT frame (SockJS cannot send custom
headers on the HTTP handshake, so HTTP-handshake-level auth is not available).

With the default configuration, Spring Security never authenticated the STOMP
session: no `@EnableWebSocketSecurity` was present, and enabling it did not help:

- Spring Security 6.4's `@EnableWebSocketSecurity` authenticates at the HTTP
  handshake only — `StompSubProtocolHandler` reads the principal from
  `WebSocketSession.getPrincipal()`, not from CONNECT-frame headers — so the
  browser's STOMP `Authorization` header is ignored.
- Under Boot 3.4.1 the annotation also fails to start the context (SS issue
  #16299): `WebSocketObservationConfiguration` registers a second
  `ObjectPostProcessor` bean (`webSocketAuthorizationManagerPostProcessor`),
  colliding with `objectPostProcessor` when the REST `SecurityFilterChain`
  is present.
- Version facts (verified 2026-08-03 via `mvn dependency:tree`): there is **no**
  `spring-framework.version` override in `pom.xml` — the Boot 3.4.1 BOM pins
  Spring Framework to **6.2.1** (spring-web, spring-messaging, spring-context,
  spring-jdbc all resolve to 6.2.1). An earlier draft claimed a "6.2.17
  override fixed the bean collision"; that claim is retracted — no override ever
  existed in the build, and because `@EnableWebSocketSecurity` was never enabled
  in this codebase, the #16299 collision was never actually exercised here.
  Without CONNECT-frame authentication the frame was rejected as unauthenticated
  (`Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]`),
  confirming handshake-only authentication.

Additionally, the messaging `AuthenticationPrincipalArgumentResolver` resolves
from the `SecurityContextHolder`, not from the message's `simpUser` header — the
header alone is no longer enough for `@AuthenticationPrincipal`. This is the
resolver's documented behavior ("Will resolve ... using
`Authentication.getPrincipal()` from the `SecurityContextHolder`" — official
Spring Security 6.4 API javadoc for
`org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver`,
docs.spring.io/spring-security/reference/6.4/api/...; the class documents this
behavior since 4.0, so it is not a 6.4-specific change) and was independently
verified by bytecode inspection of spring-security-messaging 6.4.2
(`AuthenticationPrincipalArgumentResolver` reads
`securityContextHolderStrategy.getContext().getAuthentication()`, confirmed via
`javap` on the shipped jar, 2026-08-02).

## Decision

- `UserPresenceChannelInterceptor` (already wired on the client inbound channel,
  ADR-0009) validates the CONNECT frame itself: it requires
  `Authorization: Bearer <jwt>`, decodes the token with the shared `JwtDecoder`
  bean and attaches a `JwtAuthenticationToken` as the frame user (`simpUser`).
  Missing or invalid tokens reject the frame with `MessageDeliveryException`.
- The token is cached in the WebSocket session attributes; every subsequent frame
  gets the identity re-attached (sliding TTL refresh happens at the same time).
- The live `StompHeaderAccessor` is mutated — obtained via
  `MessageHeaderAccessor.getAccessor`, as `StompSubProtocolHandler` itself does.
  `StompHeaderAccessor.wrap` is only a defensive fallback: in Spring Framework
  6.2 it copies the headers, so changes made through it never reach handlers
  (this is also how the unit tests simulate real frames:
  `setLeaveMutable(true)` + `GenericMessage` preserving the mutable headers).
- `WebSocketConfig` also registers spring-security-messaging's
  `SecurityContextChannelInterceptor` after the presence interceptor, so the
  `simpUser` identity lands in the `SecurityContextHolder` (per-executor-thread
  stack save/restore) — making `@AuthenticationPrincipal` resolve in
  `RealtimeController`.
- The controller takes `@AuthenticationPrincipal Jwt` (principal of the
  `JwtAuthenticationToken`), never a user id from the payload.

## Consequences

- `/app/typing` and `/app/message-ack` run with the authenticated subject;
  `acknowledgeDelivered` now enforces conversation membership for the acker.
- No `@EnableWebSocketSecurity`, no authorization rules for `/app/**` are needed —
  the interceptor is the only gate, which keeps the inbound channel wiring simple.
- Missing/invalid tokens produce a STOMP ERROR on CONNECT; clients see a
  connection failure instead of an anonymous session.
- Unit tests cover the six interceptor behaviours (valid/missing/invalid CONNECT,
  disconnect, frame refresh + re-attach, unknown session untouched).

## Alternatives considered

- `@EnableWebSocketSecurity` + `JwtAuthenticationProvider` — rejected: SS 6.4 has
  no CONNECT-frame authentication (handshake principal only), and SS #16299 breaks
  startup under Boot 3.4.1.
- Handshake-level auth (custom handshake interceptor issuing an `AnonymousAuthenticationToken`
  after checking the token from a handshake header) — impossible with SockJS,
  which cannot attach custom headers, and the STOMP header would not be visible at
  handshake time anyway.
- WebSocket scope proxy holding a per-session auth — the channel interceptor is
  simpler and already present for presence.
