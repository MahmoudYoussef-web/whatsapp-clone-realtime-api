# ADR-0006: User synchronization filter wiring

**Status:** Accepted · **Phase:** 1 · **Date:** 2026-08-02

## Context

Users authenticate via Keycloak JWTs; the local `users` table is a mirror populated by
`UserSynchronizer` on each authenticated request. Symptom (found by smoke testing):
`GET /api/v1/users` always returned `[]` and conversation creation failed with
`User ... not found` — the local table stayed empty. Root cause: `UserSynchronizerFilter`
was a `@Component` but was **never added to the security filter chain**, so
`synchronizeWithIdp` never ran. The filter also previously relied on the context already
being populated, which is only true **after** `BearerTokenAuthenticationFilter`.

## Decision

- Wire the filter explicitly: `http.addFilterAfter(userSynchronizerFilter, BearerTokenAuthenticationFilter.class)`
  in `SecurityConfig`.
- The filter reads `SecurityContextHolder` (never the raw request header) and skips
  anonymous/empty contexts.
- Per-user sync is bounded by a Caffeine cache (`user-sync-ttl-seconds: 60`) so the DB is
  written at most once per user per minute.

## Consequences

- Every authenticated request reflects the identity provider state (profile updates
  propagate within the TTL window).
- Users only appear in the contact list after their first login — intentional
  (same behavior as the reference app), documented in the README.
