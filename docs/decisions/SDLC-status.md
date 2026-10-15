# Six-phase SDLC status · 0.2.0

15 October 2026, Africa/Nairobi. The user authorized all six phases with local simulators for unavailable external services. This is a synthetic local reference release candidate, not an approved insurance production launch.

| Phase | Implemented and verified local behavior | External production gate |
|---|---|---|
| A | Scoped relational foundation, three state machines, asymmetric role checks, append-only history, reproducible host setup | Tenant/identity enrollment and official product terms |
| B | OpenAI/Anthropic adapters, schema/provenance validation, fixture evaluation and uncertainty handling | Live provider access, DPA/vendor/model approval, representative gold evaluation |
| C | Signed WhatsApp receipts, duplicates, encrypted persistence, media fallback, notification window/templates/opt-out previews | Meta account/template approvals, validated media scanning and real language/audio capabilities |
| D | Durable LangGraph recovery, text-evidence comparisons/resolutions, human corrections/rejections, risk flags, independent provider simulator and authenticated authoritative callbacks | Real OCR/ASR and verified Turaco contracts; production encrypted checkpoint/retention system |
| E | Human authorization, deterministic benefit, unique ledger, concurrency protection, UNKNOWN receipt-based reconciliation | Real payee/claimant verification and authorized provider disbursement contract |
| F | Local worker/retries/DLQ/replay, metrics, redacted trace adapter, security scans, SBOM, load smoke, backup/restore, CI, release packaging, Kubernetes rehearsal configuration | Executed container/kind CI, chosen AWS account/region, staging approval and operational/country signoff |

## Evidence from this machine

- Java: 20 tests passed, no failures or skipped tests, using real PostgreSQL 17.11. Tests include concurrent creation/approval/payment, tenant boundaries, callback ordering, encrypted evidence/resolution, queue backoff/DLQ, risk routing, signature/issuer/audience/expiry and ciphertext tamper/context binding.
- Python: 14 tests passed, including reopening durable conversation checkpoints, scope isolation, document conflicts, local provider persistence, mocked SDK contracts and telemetry redaction.
- Authenticated full-platform workflow passed on a freshly initialized host database and Keycloak realm. Signed channel receipt replay, provider ownership, callback replay and ambiguous payout reconciliation were exercised across real local processes.
- UI production build and npm audit passed. Python audit found no known vulnerabilities. Java CycloneDX inventory contains 100 components. Initial high/critical Jackson/Tomcat findings were patched to Jackson 2.21.7/3.1.7 and Tomcat 11.0.25; the patched inventory scan has zero high/critical findings.
- Workflow validation and Kubernetes rendering consistency passed. Container-source configuration had no high/critical failures. Kubernetes has one explicit writable-root exception for the local-only Keycloak development fixture; it is not accepted as production identity configuration.
- Bounded read smoke: 40 requests, four concurrent clients, zero errors; P50 33.72 ms, P95 420.27 ms on this machine. This is not capacity or availability certification.
- Backup/restore rehearsal passed into an isolated disposable database. The original synthetic database and encryption key were preserved.

## Deployment and release gate

The local Docker engine remains unusable, so this machine has not passed application image builds, Testcontainers' container-launch path or kind deployment. The same Java tests ran against installed PostgreSQL without H2. CI includes image builds/scanning, Testcontainers, Compose and an isolated Kubernetes deployment plus the full authenticated workflow. Checked-in workflows are not reported as executed.

No GitHub remote existed at discovery. Remote publication/CI requires an authorized repository destination; no cloud deployment or live transfer is inferred. The local release package contains source, Java binary, dashboard assets, SBOM, commit identity and checksums. Exact remote results and any remaining scanner/deployment failures must be recorded against the published commit before calling the container gate complete.

## Review findings resolved

Closed unsafe/unclear paths found during implementation: model self-confirmation, duplicate obligations, UNKNOWN blind retry, unscoped access, missing OIDC subject mapping, callback bearer-token/JWT collision, stale webhook precedence, missing source inventory in vulnerability scans, baseline dependency CVEs, conflicting evidence reservation, invalid-pagination errors and stale dashboard audit-selection responses. Startup/shutdown was made reproducible and secrets/runtime files excluded from source and container contexts.

Real data/vendor approval, native-speaker annotation, privacy/country review, real identity enrollment, official API contracts and AWS deployment authorization cannot be supplied by local code or simulation. They remain explicit external launch gates.
