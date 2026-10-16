# Local operation and release runbook

## Startup and health

Use the root README's host or Compose setup. Inspect `.cache/dev/*.log` for host-process startup; no request bodies or secrets should be printed. `/actuator/health`, `/health` and the simulator `/health` are liveness interfaces. Protected `/actuator/prometheus` exposes HTTP/JVM metrics; `/api/v1/operations/metrics` returns tenant/country-scoped backlog, dead-letter and reconciliation counts.

An authorized reviewer should investigate any dead letter or UNKNOWN payout. A growing pending outbox/provider backlog signals dependency failure. Suggested pilot alerts are dead_letters > 0, unknown_payouts > 0, provider_sync_unknown > 0 and sustained readiness failure; establish rate/latency thresholds after representative load measurement.

## Queue and provider recovery

The background worker is enabled in host/Compose/Kubernetes local setups and disabled in tests. It publishes at most 100 outbox rows and consumes at most 100 deliveries per tenant/cycle. Failed local deliveries back off exponentially and enter DEAD after three attempts. List `/api/v1/operations/dead-letters`; an approver may POST `/{id}/replays` with an evidence reference. Replay is auditable and cannot duplicate an already processed event.

Use `/claims/{id}/provider-reconciliations` to refetch the simulator's latest authoritative snapshot. Never infer state from callback arrival order. Ambiguous submissions remain linked by the internal claim ID; do not create another claim to resolve a timeout.

For UNKNOWN fake payouts, record a mock provider receipt through `/claims/{id}/mock-provider-receipts`, then call `/payout-reconciliations`. The original attempt remains immutable and no transfer is retried. A conflicting receipt is rejected. There is no real provider or operator shortcut for live disbursement.

## Evidence and corrections

REQUEST_INFORMATION reopens intake. Corrections create a new extraction version and require confirmation again. Conflicting evidence requires matching replacement evidence and an authorized resolution; quarantine cannot be bypassed. Evidence references/reason codes contain identifiers only, never clinical narratives. Retention timestamps are recorded, but production retention and secure destruction require the approved privacy design before real data.

## Workspace walkthrough (synthetic fixtures)

Sign in at the dashboard with a seeded account. Expand **Intake, evidence & recovery tools** to extract text, continue a saved conversation, attach evidence or reconcile the independent provider simulator. Each mutation locks the other claim controls until it finishes. Switching claims clears the review reference and evidence selection.

For a correction, record **Request more information**, change the unconfirmed fields, then record sample confirmation and resubmit. Load evidence to inspect whether each check uses current facts. Attach matching text and record a reviewed replacement for each unresolved conflict or stale check before recommending approval. A rejection recommendation is followed by **Finalize rejection**; approval reserves the deterministic benefit only after server checks pass.

To rehearse ambiguity, authorize a synthetic claim, choose **Simulate an unknown payout outcome**, record a separate simulator receipt and reconcile it. Verify the activity trail preserves the original attempt. Notification previews show service-window/template and opt-out rules without sending anything. Reviewers can inspect operational health and signed channel receipts; authorized approvers can replay dead letters. Real media parsing remains unsupported; the transcript checkbox explicitly opts into typed synthetic fallback.

## Backup and rollback

Run `python3 scripts/backup-restore-check.py` only on the named local synthetic database. The check uses pg_dump/pg_restore, creates its own isolated restore target, validates restored data and removes that target. Backup archives remain private under ignored `.cache/verification/`. Keep the local encryption key with protected backups; losing it makes evidence unreadable.

Before a release, verify checksum inventory and preserve a database backup plus previous image digest. Roll back application images only when the prior version is compatible with the current schema. Flyway migrations are append-only; do not edit deployed migrations or drop data to make an old binary start. A destructive database restoration requires a separately approved operational procedure and is not performed by CI.

Kubernetes uses Recreate for single-replica SQLite-backed services. Rollout failure retains PVCs. Use `kubectl rollout undo` for a compatible application revision; inspect readiness and run the synthetic smoke before continuing. Do not use this local bundle as an approved production deployment.

## Release procedure

Run Java/Python/UI verification, SBOM and vulnerability scans, authenticated full-platform demo, bounded load test, backup/restore and workflow/config validation. Commit only source/configuration, excluding `.env`, `.cache`, backups and local runtime files. `python3 scripts/package-release.py` packages tracked source, Java binary, dashboard assets, dependency inventory and SHA256 checksums into ignored `release/0.2.0/`.

The CI release-candidate job runs only after all test, image and deployment gates succeed. Publishing a repository/release or deploying to an account requires the intended destination and authorization. Record remote CI results against the exact commit; do not equate checked-in workflows with executed checks.
