# ADR-0012: Local-account check at STOMP CONNECT (ghost / deleted users)

**Status:** Accepted · **Phase:** 2 · **Date:** 2026-08-03

## Context

The WS auth gate (`UserPresenceChannelInterceptor`, ADR-0011) validates the
CONNECT frame's JWT **cryptographically only**: signature, issuer, audience and
`exp` via the shared `JwtDecoder`. It never consults the local users table. Two
verified negative-path checks (2026-08-03, `resources/ws-negative-checks.js`)
expose the gap:

- **C4a** — a user that exists in Keycloak but **never in the local DB** (ghost)
  connects successfully with a valid token, and even gets a Redis presence entry
  (`PresenceService.setOnline`).
- **C4b** — a user **deleted after token issuance** keeps an accepted session
  until the token's `exp` (default realm lifespan 300 s). Deletion is never
  observed by the WS layer.

Impact is limited today — message handlers reject unknown users via
`requireParticipant` (`MessageService`/`TypingService`) — but the session is a
"zombie": presence lies, and any future handler that trusts the authenticated
subject would silently process frames for a deleted account.

## Options

### Option A — lookup the local users table at CONNECT

Check `userRepository.existsById(jwt.getSubject())` (or similar) inside
`UserPresenceChannelInterceptor.authenticate()` and reject the CONNECT frame
with `MessageDeliveryException` when the subject is unknown.

- **Pros:** closes C4a and C4b at the door; no per-frame cost; presence stays
  truthful; matches the policy expectation that the WS gate and the local
  account set are consistent.
- **Cons:** one extra DB query per connection (L1/L2 cache makes it cheap for
  warm ids, but the read is on the hot path); makes the WS gate dependent on
  local-DB synchronization — a Keycloak user that has not yet been synced to
  the local table (sync lag, new-registration race) would be locked out of WS
  even with a valid token; requires deciding the source of truth for "does the
  account exist" (Keycloak vs local DB).

### Option B — keep cryptographic-only validation (status quo)

Document ghost/deleted sessions as accepted behavior until token `exp`.

- **Pros:** zero cost, zero new failure modes, no sync dependency; sessions are
  bounded by the token lifetime.
- **Cons:** C4a/C4b remain open; a deleted user's session keeps presence and
  any future subject-trusting handler can be reached until `exp`; the policy's
  C4a/C4b checks fail by design.

### Option C — soft check: reject only known-deleted, allow unknown

Check a negative signal instead of the full table, e.g. a "deleted users"
denylist (or `active=false` flag) consulted at CONNECT; unknown subjects pass.

- **Pros:** no sync-lag lockout for brand-new users; closes C4b (deletion is
  explicit) with one indexed lookup on a small set.
- **Cons:** C4a stays open (ghost users pass); the denylist is a second source
  of truth that must be maintained wherever accounts are deleted; more moving
  parts than A or B.

## Decision

**Option A — lookup the local users table at CONNECT.** After JWT signature/exp
verification succeeds, `UserPresenceChannelInterceptor.authenticate()` verifies
that the token's email exists in the local `users` table (reusing
`UserRepository.findByEmail`, the same lookup `UserSynchronizer` performs on the
REST side) **before** calling `PresenceService.setOnline` or accepting the
CONNECT. Unknown subjects are rejected with a `MessageDeliveryException` (STOMP
ERROR frame; `setOnline` is never called). The check runs once per connection —
not per frame — because `requireParticipant` already protects downstream
actions; this closes the presence-key/zombie-session leak specifically.

**Sync-lag risk (accepted):** a user whose account was created in Keycloak but
not yet written to the local table would be rejected at CONNECT. This is
accepted because `UserSynchronizerFilter` already performs the same sync on the
REST side and the WS path now applies the same guarantee — the check does not
introduce a new failure mode, it extends the existing one. It also means a
never-synced user cannot get a WS session until the first REST call syncs them.

## Consequences

- C4a (ghost user, valid token, never in local DB) and C4b (user deleted after
  token issuance) now reject the CONNECT with an ERROR frame; no presence key
  is created for unknown subjects.
- One extra repository lookup per connection on the WS hot path (indexed, cache
  friendly); no per-frame cost.
- The ADR-0011 gate now enforces both cryptographic validity and local-account
  existence at CONNECT; `resources/ws-negative-checks.js` C4a/C4b flip from
  FAIL to PASS without changing their assertions (they already expected
  rejection).

## Alternatives considered

- Revoking Keycloak sessions on deletion (`admin API` logout) — out of scope:
  the app has no Keycloak admin integration today, and token-bound sessions
  would still survive until `exp`.
