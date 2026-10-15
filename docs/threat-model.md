# Sprint 1 trust boundaries

Version 0.2 adds authenticated encrypted evidence/receipt storage, signed WhatsApp intake, durable local conversations, provider snapshot reconciliation, risk routing and queue recovery. This original table records the Sprint 1 baseline; [ADR-002](decisions/ADR-002-local-platform.md) and the current status/runbook describe the new controls and explicit simulator limits.

This loopback-only synthetic environment is not approved for real insurance or medical records.

| Threat | Implemented control | Remaining release gate |
|---|---|---|
| Unauthorized browser/API access | Asymmetric JWT, expiry/issuer/audience, method roles, signed scope, CORS allowlist, token in memory | Production OIDC/PKCE, enrolled identities, TLS |
| Tenant/country crossing | Scoped SQL and composite foreign keys; negative tests | Independent access-control review, DB least privilege |
| Model fabricates/confirms data | Schema v2, quote checks, confirmation downgraded, explicit confirmation, deterministic decisions | Representative multilingual evaluation and document verification |
| AI authorizes money | AI has no business JWT/tools; approval and mock payout use separate role checks | Production separation-of-duty policy |
| Duplicate requests/races | Fingerprints, unique keys, claim lock, unique obligation and attempt | Load tests and real provider contracts |
| Unknown provider result | Persist UNKNOWN and forbid retry | Reconciliation tooling before real providers |
| History tampering | Triggers deny UPDATE/DELETE of audit/transitions/reviews/extractions/attempts | Admin controls, retention/export and immutable external storage |
| Secret/medical leakage | Ignored generated secrets, no body logging, redacted errors, store=false | Encryption, managed secrets, vendor/legal review, telemetry policy |
| Unverified claimant/payee | Authorized staff attestation; no model/phone auto-verification | Independent real identity and payee ownership checks |
| Accidental integration | Startup fails outside standalone; no external claims/payment client; real model gated | Verified schemas, auth, sandbox contracts, country signoff |

Audit/outbox contain reference identifiers rather than transcripts. Extraction versions contain synthetic quoted evidence. Reason codes/evidence references must contain codes/identifiers only. PostgreSQL administrators can bypass triggers; history is append-only for normal application operations, not tamper-proof against administrators.

Keycloak start-dev, direct password grants, seeded passwords and DB owner credentials are local-only. Compose is not a production deployment manifest. AWS/Kubernetes/SQS, real residency routing, consent collection, rate limits, document scanning and retention enforcement remain later work. JSON acceptance never authorizes adding real patient data.
