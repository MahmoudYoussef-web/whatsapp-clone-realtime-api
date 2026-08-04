# ADR-0007: WebSocket converter must use the Boot-configured ObjectMapper (jsr310)

**Status:** Accepted · **Phase:** 1 · **Date:** 2026-08-02

## Context

Symptom: every message notification failed on the wire with
`InvalidDefinitionException: Java 8 date/time type java.time.LocalDateTime not supported...
add jackson-datatype-jsr310`. Root cause: `WebSocketConfig.configureMessageConverters`
built a bare `new ObjectMapper()` for the STOMP message converter. Spring Boot's
auto-configured `ObjectMapper` bean registers the JavaTimeModule; the manually constructed
one did not — so serializing `MessageResponse.createdAt` (a `LocalDateTime`) blew up
whenever a `MESSAGE`/`DELIVERED`/`READ` notification was pushed.

## Decision

- Inject the application's `ObjectMapper` bean into `WebSocketConfig` and set it on the
  `MappingJackson2MessageConverter` instead of creating a new one.

## Consequences

- WebSocket payloads serialize exactly like REST payloads (same date format, same naming).
- Uncovered only by integration tests that exercise the full Spring context
  (`SchemaMigrationIntegrationTest`) — a unit test could not catch it.
