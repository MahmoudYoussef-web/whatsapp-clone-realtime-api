# ADR-0013: Re-validating token expiry on frames after CONNECT

**Status:** Accepted · **Phase:** 2 · **Date:** 2026-08-03

## Context

The WS auth gate validates the JWT **once, at CONNECT** (`jwtDecoder.decode` in
`UserPresenceChannelInterceptor.authenticate()`, ADR-0011). Every subsequent
frame only refreshes the Redis presence TTL and re-attaches the session-cached
`JwtAuthenticationToken` — the token is never re-decoded, so its `exp` claim is
never re-checked. The message-level `AuthorizationChannelInterceptor`
(`@EnableWebSocketSecurity` default) only verifies `isAuthenticated()`, not
token validity.

Verified negative-path check **C5** (2026-08-03,
`resources/ws-negative-checks.js`): a client whose token expires mid-connection
(30 s lifespan, waited past `exp` + the 60 s Nimbus clock skew) keeps sending
frames and the server still relays them — a TYPING frame was delivered to the
peer ~100 s after expiry, with no error on the client's connection. This is the
only finding with direct security impact: a stolen/leaked token that survived
its lifetime can still drive authenticated handlers for the whole connection.

## Options

### Option A — full re-decode on every frame

In the interceptor's per-frame path, re-run `jwtDecoder.decode(...)` with the
raw token cached in the session attributes, then refresh the `simpUser`
identity from the fresh `Jwt`.

- **Pros:** closes C5 completely; re-validates signature, issuer, audience and
  `exp` on every frame — a token revoked at Keycloak (via JWKS rotation) also
  stops working; strongest guarantee.
- **Cons:** one RSA signature verification per frame on the inbound hot path
  (Nimbus caches JWKS and verifier, but each call still does a full verify;
  measurable under high message rates); more CPU per frame than the presence
  work itself; needs care to keep the session-cached token and the decoded one
  consistent for `@AuthenticationPrincipal`.

### Option B — cheap `exp` check of the cached token per frame

Keep the stored `Jwt` and, on each frame, compare its `exp` against the current
time plus the same clock skew the decoder uses (`JwtTimestampValidator` /
`NimbusJwtDecoder.DEFAULT_CLOCK_SKEW_SECONDS = 60`); reject the frame with a
`MessageDeliveryException` (or drop the session) once expired.

- **Pros:** closes C5 (expiry is enforced on every frame) at near-zero cost — a
  single long comparison, no crypto; no JWKS dependency at frame time; the
  client sees the same 60 s skew window it already gets at CONNECT, so behavior
  is consistent.
- **Cons:** does not detect other revocation (signature/key rotation, audience
  changes) — only `exp`; a malicious actor replaying a captured pre-expiry
  token still works until `exp` (same as CONNECT, and same as Option A unless
  Keycloak rotates keys).

### Option C — accept CONNECT-only validation (status quo)

Document that token validity is enforced at CONNECT only; frames ride the
session identity until disconnect.

- **Pros:** zero per-frame cost; simplest; matches many STOMP deployments.
- **Cons:** C5 stays open — a leaked token keeps its session alive indefinitely
  past expiry (until the user disconnects), which is precisely the failure the
  policy's negative-path checks target.

## Decision

**Option B — cheap `exp` check of the cached token per frame.** On every
non-CONNECT frame, `UserPresenceChannelInterceptor` validates the
session-cached token's `exp` with a `JwtTimestampValidator` — the same class
and the same default 60 s clock skew the shared auto-configured `JwtDecoder`
uses at CONNECT — before refreshing presence. An expired token rejects the
frame with a `MessageDeliveryException` (STOMP ERROR frame + session close via
`CloseStatus.PROTOCOL_ERROR`, matching the CONNECT-time rejection shape); the
frame is never silently dropped. No signature re-verification, no JWKS access,
and no wire-level decode beyond the already-resolved session token.

**Option A (full re-decode per frame) rejected:** an RSA signature verification
on every frame is unnecessary cost on a hot path (typing indicators, frequent
sends) when the only realistic threat here is a stale/expired token, not
mid-session key rotation. The CONNECT-time full verification (signature,
issuer, audience) stays untouched.

## Consequences

- C5 (token expires mid-connection) now rejects subsequent frames: ERROR frame
  + session closed with `CloseStatus.PROTOCOL_ERROR`; nothing is relayed after
  expiry.
- Pre-expiry frames in the same session are unaffected (regression guard in
  tests); the 60 s skew behaves identically to the CONNECT-time check.
- Per-frame cost is one instant comparison — no crypto.
- ADR-0011's "any frame → re-attach session user" bullet is superseded for the
  expiry aspect by this ADR.

## Alternatives considered

- Server-side session revocation (Keycloak admin API on logout/delete) — out of
  scope for this ADR; does not help mid-connection expiry, which needs one of
  the options above regardless.
