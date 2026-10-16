# Turaco Insurance reference · 0.2.0

A synthetic implementation of all six phases in [Handoff v5](docs/source/turaco_insurance_handoff_v5.md): intake, extraction, conversation, review, provider synchronization, simulated payouts and operational recovery. Independent reference implementation; not an official Turaco system.

**No live payments or real patient data.** Local simulators stand in for external services that require access or validated contracts. [Implementation and verification status](docs/decisions/SDLC-status.md) distinguishes implemented behavior from external release gates.

## Cloud presentation

The presentation uses a Cloudflare Workers Free hub and the existing application in a GitHub Codespace's Docker stack. All presenter-facing documents, release downloads, source and handoff are hosted. See the [cloud presentation guide](docs/cloud-presentation.md). No local process is required. Included compute/storage quotas apply; retain a $0 Codespaces budget with usage blocking.

## Start the local workspace

### Without Docker — verified host-run path

Requires Java 25, Python 3.12–3.14, uv, Node 24+ with npm, and PostgreSQL 17 command-line tools on PATH. No external credentials are needed. Keycloak 26.4.0 downloads from its official release on first setup. Keep several GB of free storage.

```sh
python3 scripts/dev.py start
API_URL=http://127.0.0.1:18080 python3 scripts/demo.py
API_URL=http://127.0.0.1:18080 python3 tests/e2e/full_platform.py
```

Open [the review workspace](http://localhost:5173). Local fixture login: `approver` / `local-demo-only`. Roles: officer handles intake; reviewer records verification/recommendations; approver can complete the synthetic journey. These accounts and the password flow are explicitly local fixtures. Tokens remain in browser memory.

```sh
python3 scripts/dev.py status
python3 scripts/dev.py stop
# Preserve data and reuse existing builds:
python3 scripts/dev.py start --skip-build
```

Runtime data, logs and PID records are under ignored `.cache/dev/`. The helper starts an isolated, password-protected PostgreSQL cluster on 55433, API on 18080, Keycloak on 8081, extraction on 8000, provider simulator on 9000 and dashboard on 5173. It refuses occupied ports and stops only identified managed processes. Stopping preserves synthetic data. Never remove `.env`'s encryption key while retaining encrypted evidence.

### Docker Compose

```sh
python3 scripts/setup-local.py
docker compose up -d --build
python3 scripts/demo.py
python3 tests/e2e/full_platform.py
```

Published ports bind to loopback. Default API port is 8080; change `API_PORT` in `.env` and set `API_URL` for scripts if occupied. Do not run Compose and the host stack on the same ports. `docker compose down` preserves named volumes; `docker compose down -v` explicitly discards this prototype's local data.

The setup script generates private, ignored `.env` secrets and preserves existing values. Local Keycloak users are confined to the dedicated realm fixture. Docker builds and the Kubernetes deployment rehearsal are checked by CI; consult the status note for which gates have actually run.

## What is implemented

| Phase | Working local behavior |
|---|---|
| A · Foundation | Scoped customer/product/policy/claim model, PostgreSQL/Flyway, OIDC roles, immutable audit and state transitions |
| B · Extraction | FastAPI/Pydantic schema v2, deterministic fixtures, optional OpenAI and Anthropic adapters, stratified regression report |
| C · Channel | WhatsApp signature verification/challenge, durable duplicate-safe encrypted receipts, explicit media/human fallback, service-window/template previews, opt-out |
| D · Review and integration | Persistent LangGraph conversations, encrypted text evidence and discrepancy resolution, deterministic risk flags, correction/rejection loops, independent provider simulator, authenticated callbacks and authoritative reconciliation |
| E · Payment | Deterministic benefit, human authorization, unique obligations, stable-key fake attempts, UNKNOWN retry block and receipt-based reconciliation |
| F · Operations and release | Background local outbox/queue simulator, bounded retries/DLQ/replay, scoped operational metrics, Prometheus endpoint, optional redacted Langfuse traces, SBOM/security checks, backup/restore rehearsal, load smoke, CI, release packaging and synthetic Kubernetes bundle |

The UI includes text extraction, saved conversations, unconfirmed corrections, requests for information, approval/rejection recommendations, and final rejection. Evidence tools distinguish current checks from stale ones and record reviewed replacements. Operators can submit to the provider simulator, recover ambiguous fake payouts using separate receipts, process signed channel receipts, preview notification rules, and authorize dead-letter replay. Review references and server permissions remain required. Provider status changes and signed callback scenarios are exercised by the independent simulator harness and documented APIs. Templates only produce previews; no message is sent.

Demo Hospital Cash terms: KES 1,500 per night, maximum 30 nights, 30-day waiting period, 2026 coverage. A three-night fixture pays simulated KES 4,500. These are invented terms, not Turaco policy terms. Java recomputes benefit amounts and ignores model-derived nights.

## Verify

```sh
# Default Java gate uses real PostgreSQL Testcontainers, never H2:
cd services/insurance-api
./mvnw -B verify org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom
# With an existing DISPOSABLE test database, use TEST_DATABASE_URL/USER/PASSWORD instead.

# From services/ai-service:
uv sync --frozen
uv run --frozen pytest -q
uv run --frozen pip-audit
uv run --frozen python -m app.evaluation ../../datasets/eval/hospital-cash.json --check

# From apps/claims-dashboard:
npm ci
npm run build
npm audit --audit-level=high

# From the repository root, with the host stack running:
API_URL=http://127.0.0.1:18080 python3 scripts/load-check.py
python3 scripts/backup-restore-check.py
API_URL=http://127.0.0.1:18080 python3 scripts/export-contracts.py

# From the repository root, after the Java/SBOM and dashboard builds:
python3 -m unittest discover -s tests -p 'test_release_tools.py'
# Packaging requires a clean committed worktree:
python3 scripts/package-release.py
python3 scripts/verify-release.py --directory release/0.2.0
```

The backup rehearsal only accepts named local synthetic databases, restores into a newly created disposable database, validates it, then removes that restored copy. CI exercises Testcontainers, Compose, application-image scanning and an isolated kind deployment before packaging a release candidate. It does not publish or deploy to AWS automatically.
The source package preserves raw Git blob bytes and the verifier checks every file against the manifest's commit, independent of checkout line-ending filters. The packager still requires a clean worktree.

## Optional real-model adapters

Set `AI_PROVIDER=openai` or `anthropic`, `REAL_PROVIDER_ENABLED=true`, and the matching `OPENAI_API_KEY`/`OPENAI_MODEL` or `ANTHROPIC_API_KEY`/`ANTHROPIC_MODEL`. SDK versions are locked in `uv.lock`; model selection is explicit. Only synthetic data is authorized. Both adapters enforce provenance and cannot confirm critical fields or access approval/payment credentials. OpenAI uses [Responses structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs); Anthropic uses a forced extraction tool with schema validation.

Langfuse is disabled by default. Its opt-in adapter exports only language, provider, schema version and missing-field counts; no transcript, evidence quote, claimant/tenant ID or prompt is exported. Automatic LangSmith state tracing is disabled. The local SQLite conversation store is for synthetic, single-replica use; production requires approved encrypted persistence and retention.

## Release and operation

- [Phase status and actual verification](docs/decisions/SDLC-status.md)
- [Architecture](docs/architecture.md), [API behavior](docs/api-contracts.md), [generated contracts](docs/openapi/)
- [Operations, backup and rollback](docs/runbook.md)
- [Kubernetes deployment rehearsal](infrastructure/kubernetes/README.md)
- [Security boundaries](docs/threat-model.md), [evaluation scope](docs/evaluation-plan.md)
- [Source reuse](docs/decisions/ADR-001-foundation.md), [six-phase decisions](docs/decisions/ADR-002-local-platform.md)

`APP_MODE=integration` fails closed: no verified official Turaco adapter or disbursement contract exists. Speech/PDF/image OCR and real malware scanning remain human-assisted/external validation gates; the local document parser does not pretend to perform those tasks. Real identity enrollment, policy terms, vendor agreements, residency/retention and country approvals are required before real data. No cloud resources or real transfers are created by the local stack.
