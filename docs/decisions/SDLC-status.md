# Six-phase SDLC status · 0.2.0

15 October 2026, Africa/Nairobi. The user authorized all six phases with local simulators for unavailable external services. This is a synthetic local reference release candidate, not an approved insurance production launch.

## Verified source

- Public repository: https://github.com/kimtour/turaco-insurance
- Branch: `codex/full-sdlc`
- Tested application commit: `57e361ea0ea9d27e7c0730eb2f6c20cdc17b1a20`
- Application version: `0.2.0`
- Successful CI run: https://github.com/kimtour/turaco-insurance/actions/runs/38791009127

This status update and the artifact verification record are documentation added after the tested commit. They do not change application code and are not represented as a new full-gate execution. Git history records their documentation commit; the runtime evidence below applies to the exact tested commit above.

## Phase coverage

| Phase | Implemented and verified local behavior | External production gate |
|---|---|---|
| A | Scoped relational foundation, three state machines, asymmetric role checks, append-only history, reproducible host setup | Tenant/identity enrollment and official product terms |
| B | OpenAI/Anthropic adapters, schema/provenance validation, fixture evaluation and uncertainty handling | Live provider access, DPA/vendor/model approval, representative gold evaluation |
| C | Signed WhatsApp receipts, duplicates, encrypted persistence, media fallback, notification window/templates/opt-out previews | Meta account/template approvals, validated media scanning and real language/audio capabilities |
| D | Durable LangGraph recovery, text-evidence comparisons/resolutions, human corrections/rejections, risk flags, independent provider simulator and authenticated authoritative callbacks | Real OCR/ASR and verified Turaco contracts; production encrypted checkpoint/retention system |
| E | Human authorization, deterministic benefit, unique ledger, concurrency protection, UNKNOWN receipt-based reconciliation | Real payee/claimant verification and authorized provider disbursement contract |
| F | Local worker/retries/DLQ/replay, metrics, redacted trace adapter, security scans, SBOM, load smoke, backup/restore, executed Compose/kind rehearsal and release packaging | Chosen AWS account/region, staging approval and operational/country signoff |

## Evidence from this machine

- Java: 20 tests passed, no failures or skipped tests, using real PostgreSQL 17.11. Tests include concurrent creation/approval/payment, tenant boundaries, callback ordering, encrypted evidence/resolution, queue backoff/DLQ, risk routing, signature/issuer/audience/expiry and ciphertext tamper/context binding.
- Python: 14 tests passed, including reopening durable conversation checkpoints, scope isolation, document conflicts, local provider persistence, mocked SDK contracts and telemetry redaction.
- Authenticated full-platform workflow passed on a freshly initialized host database and Keycloak realm. Signed channel receipt replay, provider ownership, callback replay and ambiguous payout reconciliation were exercised across real local processes.
- UI production build and npm audit passed. Python audit found no known vulnerabilities. Java CycloneDX inventory contains 100 components. Initial high/critical Jackson/Tomcat findings were patched to Jackson 2.21.7/3.1.7 and Tomcat 11.0.25; the patched inventory scan has zero high/critical findings.
- Workflow validation and Kubernetes rendering consistency passed. Container-source configuration had no high/critical failures. Kubernetes has one explicit writable-root exception for the local-only Keycloak development fixture; it is not accepted as production identity configuration.
- Bounded read smoke: 40 requests, four concurrent clients, zero errors; P50 33.72 ms, P95 420.27 ms on this machine. This is not capacity or availability certification.
- Backup/restore rehearsal passed into an isolated disposable database. The original synthetic database and encryption key were preserved.

## Remote CI and release evidence

All five jobs passed in run `38791009127` for commit `57e361e`:

| Job | Verified scope |
|---|---|
| java | 20 tests using PostgreSQL Testcontainers, SBOM generation and HIGH/CRITICAL dependency scan |
| python | Locked installation, 14 tests and pip-audit |
| dashboard | Production build and npm audit |
| compose-demo | Application image builds, authenticated demos, full-platform recovery, load smoke, API export, Java application-image scan, kind deployment and authenticated Kubernetes demos |
| release-candidate | Java/UI build, packaging and artifact upload after all preceding jobs passed |

Artifacts were confirmed non-expired on 15 October 2026:

- `java-verification`: artifact ID `12000651361`
- `generated-api-contracts`: artifact ID `12000196781`
- `synthetic-release-candidate-0.2.0`: artifact ID `11999099722`

Codex independently downloaded and verified the release artifact on 15 October 2026, Africa/Nairobi. All five SHA256 entries passed, with no missing or extra files. Its manifest identifies version `0.2.0`, commit `57e361ea0ea9d27e7c0730eb2f6c20cdc17b1a20`, synthetic classification and disabled live payments. The Java binary, UI build, source archive and 100-component CycloneDX inventory are present. All 103 source files match the tested commit byte for byte.

The source and UI archives were inspected internally for unsafe paths and excluded environment/runtime/cache files; known generated local secret values were checked without printing them. The placeholder `.env.example` and documented synthetic fixture credentials are expected. This check does not establish the absence of every possible credential. Exact hashes, artifact metadata, timestamps, check results and scope limits are recorded in [RELEASE-0.2.0-verification.json](RELEASE-0.2.0-verification.json).

The local Mac Docker engine remains unusable. GitHub's Linux runners supplied the successful Docker, Testcontainers and kind execution; this is no longer a pending engineering gate. The source archive preserves documentation as it existed at the tested commit; this later closeout note does not alter that archive.

## Local release package

`release/0.2.0/` now contains an unchanged copy of the verified CI artifact for `57e361e`, including its original manifest and checksums. The earlier local package, screenshot and `verification.json` for `1615427bf24152389798baaefd385b8de06ac7d9` were preserved under ignored `.cache/verification/superseded-release-1615427/` and labeled as superseded. Their earlier host measurements remain useful evidence, but that package is not the current CI release candidate.

No GitHub release or AWS/cloud deployment has been created. Repository publication and CI execution do not authorize a production launch.

## Verification limits

- The 40-read, four-client load smoke is not capacity or availability certification.
- English/Swahili/mixed-language synthetic fixtures are not representative or native-speaker accuracy certification.
- Java dependencies and the Java application image passed HIGH/CRITICAL scans; Python and npm audits also passed. Scanning did not cover every third-party container image.
- The kind rehearsal is not production security or network-policy enforcement certification. Keycloak's writable root remains a documented development-fixture exception.
- Real OCR, ASR and malware validation remain unsupported or human-assisted.

## Review findings resolved

Closed unsafe/unclear paths found during implementation: model self-confirmation, duplicate obligations, UNKNOWN blind retry, unscoped access, missing OIDC subject mapping, callback bearer-token/JWT collision, stale webhook precedence, missing source inventory in vulnerability scans, baseline dependency CVEs, conflicting evidence reservation, invalid-pagination errors and stale dashboard audit-selection responses. Startup/shutdown was made reproducible and secrets/runtime files excluded from source and container contexts.

Real data/vendor approval, native-speaker annotation, privacy/country review, real identity enrollment, official API contracts and AWS deployment authorization cannot be supplied by local code or simulation. They remain explicit external launch gates.
