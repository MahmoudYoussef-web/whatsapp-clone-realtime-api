# ADR-0008: Testcontainers on Docker Desktop with engine 29 (API version)

**Status:** Accepted · **Phase:** 1 · **Date:** 2026-08-02

## Context

Symptom: `mvn verify` on this machine skipped all integration tests with
`Could not find a valid Docker environment` — every strategy failed with
`BadRequestException (Status 400)` and an empty `/info` payload, while `docker ps` worked
fine from the CLI. Root cause: Docker Desktop (engine 29.x) raised the minimum Docker API
version to 1.44; Testcontainers 1.x (managed by Spring Boot 3.4.1) still defaults the
docker-java client to API 1.32, and the engine's named-pipe endpoint rejects it with 400.
This is tracked upstream in testcontainers/testcontainers-java#11212/#11210.

## Decision

- Pin the docker-java API version for tests in a project-local
  `src/test/resources/docker-java.properties`:
  `api.version=1.44`.
- Tests remain self-skipping when Docker is unavailable
  (`@Testcontainers(disabledWithoutDocker = true)`).

## Consequences

- Integration tests run against engine 29 without requiring a machine-level file in
  `$HOME/.docker-java.properties` or a Docker Desktop downgrade.
- When the project eventually upgrades Testcontainers 2.x (default 1.44), the override
  becomes a no-op and can be removed.
