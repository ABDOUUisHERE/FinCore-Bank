# ADR 0001 — Independent services introduced incrementally

Status: accepted on 2026-10-22, following approval to start Phase 1.

## Context

The target repository has no application. The master specification requires distributed services, owned data and recovery behavior, while prioritizing financial correctness over service count.

## Decision

Use independent Java 21/Spring Boot services in a monorepo. Phase 1 creates only Gateway and a minimal Customer resource server alongside Keycloak and the portal shells. Ledger follows in Phase 2; Account and Payment in Phase 3. Each service owns its database and credentials, with no cross-service SQL or shared mutable domain model. Start with REST; add Kafka when consumer behavior is implemented.

## Alternatives and consequences

A modular monolith would simplify setup and intra-process transactions, but postpone the distributed financial recovery requirements. Creating every requested service immediately would add empty infrastructure and superficial coverage. The chosen approach incurs network and local-resource costs but keeps each stage demonstrable. Service extraction is not needed to establish the first financial boundary. Revisit deployment granularity only with measured constraints and a new ADR.
