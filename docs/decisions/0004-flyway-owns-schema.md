# ADR-0004: Flyway owns the schema

**Status:** Accepted · **Phase:** 1 · **Date:** 2026-08-02

## Context

The original app let Hibernate generate the schema from entities (`ddl-auto: update`).
Schema drift is invisible, destructive migrations are impossible, and local DBs silently
diverge from production.

## Decision

- All DDL lives in Flyway migrations (`src/main/resources/db/migration/V1__init_conversations.sql`).
- `spring.jpa.hibernate.ddl-auto: validate` — Hibernate validates the entities against the
  migrated schema at startup and fails fast on mismatch.
- New features (Phase 2) must add a new numbered migration (`V2__...`), never edit V1.

## Consequences

- Reproducible schema in any environment; the Testcontainers integration test
  (`flywayMigrationsAreApplied`) asserts migrations apply cleanly against a fresh Postgres.
- Slightly more ceremony: every schema change needs a migration file.
