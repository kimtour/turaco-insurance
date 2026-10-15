# ADR-001 · Standalone Hospital Cash foundation

Date: 15 October 2026. Status: implemented for local synthetic Sprint 1.

## Source reuse

Source: https://github.com/kimtour/insurance-policy-api at `16a1ff04c867693f12fb912bbc72156942845eb1`. Inspected in a temporary checkout; that repository was not modified or pushed.

Copied mvnw, mvnw.cmd and wrapper properties unchanged. Retained com.sam.insurance, Java 25, Spring Boot 4.1.1, PostgreSQL/Flyway, Actuator and controller/service separation. GlobalExceptionHandler adapts the source validation-field mapping, adds codes/correlation IDs and removes raw business-message logging. Source validation informed strict policy dates and decimal constraints. Other domain code was rewritten; old entities/controllers were not migrated unchanged.

## Decisions

- Scoped JDBC replaces JPA for this slice to make tenant predicates, locks and PostgreSQL conflicts directly reviewable. No H2 substitutes for PostgreSQL.
- UUIDs, normalized customer/product/coverage records and scoped foreign keys replace long IDs and embedded customer information. No hard delete.
- Keycloak replaces demo login/symmetric tokens. Only the dedicated local realm fixture seeds users.
- Separate intake/provider/payout snapshots and immutable history replace one status. Incoming extraction cannot self-confirm.
- Standalone approval is authoritative locally. Integration mode refuses startup pending verified contracts and adapters.
- Approval reserves one unique obligation transactionally. Fake payout persists a stable-key attempt; UNKNOWN blocks retries.
- Direct OpenAI SDK access is optional; mock is default. Anthropic/LangGraph await evaluation or conversation requirements.
- Outbox records commit with mutations; asynchronous relay is not claimed in this sprint.

## Limits and open decisions

Identity/payee verification and confirmation are synthetic staff attestations. Full-demo approver combines duties for the walkthrough; production separation remains undecided. Demo terms are not Turaco terms. Uploads/OCR, self-service, correction/rejection loops, speech, WhatsApp, Turaco synchronization, notifications, reconciliation and production infrastructure remain later phases.

The Java/Spring baseline compiles and PostgreSQL integration tests run. Complete container/Testcontainers verification depends on a working engine; actual outcomes are recorded separately in the sprint verification note.
