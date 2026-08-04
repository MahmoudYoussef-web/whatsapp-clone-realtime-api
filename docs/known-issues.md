# Known Issues

Tracked here because the project has no issue tracker. Each entry is a symptom
+ repro, deliberately **not investigated or fixed** unless a linked ADR says
otherwise.

## KI-001: /v3/api-docs returns 401 without a token and 500 with one

- **Reported:** 2026-08-03
- **Symptom:** `GET /v3/api-docs` without `Authorization` → **401**, despite
  `/v3/api-docs` (and `/v3/api-docs/**`) being in the `permitAll` matchers of
  `SecurityConfig`. `GET /v3/api-docs` with a valid Keycloak Bearer token →
  **500** (server error).
- **Repro:** fresh build of the current code (verified on
  `whatsappclone-0.0.1-SNAPSHOT.jar` built 2026-08-03, so **not** a stale-build
  artifact), Docker stack up, `curl http://localhost:8080/v3/api-docs`.
- **Scope:** out of the WS auth work (ADR-0011/0012/0013). Flagged by the
  Round 2 verification; explicitly **not** investigated or fixed there.
- **Possible leads (unverified):** springdoc route registration vs the
  resource-server filter chain; `KeycloakJwtAuthenticationConverter` failure
  during doc generation (500 path). Do not treat as root cause.
