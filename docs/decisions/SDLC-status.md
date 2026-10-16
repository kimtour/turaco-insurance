# SDLC status

Independent synthetic reference implementation. Not an official Turaco system.

## Verified source

- Repository: https://github.com/kimtour/turaco-insurance (public)
- Branch: `codex/full-sdlc`
- Release package source commit: `24935a7ff5d69cb677d0444927970da8a0695218`
- Application version: 0.2.0
- CI run 17: https://github.com/kimtour/turaco-insurance/actions/runs/38853857653
- This remains the separately verified 0.2.0 package source. Later branch changes are not
  relabeled as that release artifact.

## Remote CI result (run 38853857653)

| Job | Result | Scope |
|---|---|---|
| java | Passed | PostgreSQL Testcontainers tests, SBOM, HIGH/CRITICAL dependency scan |
| python | Passed | Locked install, tests, pip-audit |
| dashboard | Passed | Production build, npm audit |
| compose-demo | Passed | Image builds, authenticated demos, recovery workflow, load smoke, API export, Java application-image scan, kind deployment and authenticated Kubernetes demos |
| release-candidate | Passed | Eight release-tool tests, build, packaging, provenance verification and artifact upload |

## Artifacts (non-expired at 2026-10-16)

- `java-verification`: ID 12026004800
- `generated-api-contracts`: ID 12026054830
- `synthetic-release-candidate-0.2.0`: ID 12026128306, expires 2027-01-14
- Release artifact ZIP SHA-256: `d08022634902b848d4de7e97c249678c2a52bfe68c040b8cad8456769a186038`

The artifact was downloaded and verified locally on 2026-10-16. Its ZIP digest
matched GitHub's artifact digest and all five payload checksums passed. The
manifest identifies commit `24935a7ff5d69cb677d0444927970da8a0695218` / version
`0.2.0`. All 111 source files match that commit's raw Git blobs byte for byte;
the dashboard archive has three files and the SBOM has 100 components. Full
payload hashes and verification scope are recorded in
[RELEASE-0.2.0-run-17-verification.json](RELEASE-0.2.0-run-17-verification.json).

- `synthetic-extraction-evaluation`: ID 12026680507; the checked-in report is
  [EXTRACTION-run-17-evaluation.json](EXTRACTION-run-17-evaluation.json).

## Completed workspace review

- Local checks: 23 Java tests, 17 Python tests, eight release-tool tests, dashboard build,
  all-six-phase authenticated E2E and isolated PostgreSQL backup/restore passed.
- Browser checks: information request, correction, reconfirmation/resubmission, conflicting
  evidence and reviewed matching replacement, human authorization, UNKNOWN fake payout
  receipt/reconciliation, opt-out preview, health/dead-letter listing and final rejection.
- Rejection left payout NOT_AUTHORIZED; recovered payout reached CONFIRMED without retry.
- Sign-out cleared claim data; the reviewer account had no approval or payment controls.
- The runbook documents these controls. Browser screenshots and detailed local QA results
  remain under ignored `.cache/verification/`.

## Local release package

`release/0.2.0/` contains the independently verified run 17 artifact for commit
`24935a7ff5d69cb677d0444927970da8a0695218`. The run 15 candidate is preserved under ignored
`.cache/verification/superseded-release-83260ee/`, with its original
[verification evidence](RELEASE-0.2.0-run-15-verification.json). The previous local candidate for
`57e361ea0ea9d27e7c0730eb2f6c20cdc17b1a20` is preserved under ignored
`.cache/verification/superseded-release-57e361e/`; its original verification record
remains [RELEASE-0.2.0-verification.json](RELEASE-0.2.0-verification.json). The
earlier package for `1615427bf24152389798baaefd385b8de06ac7d9` remains under
`.cache/verification/superseded-release-1615427/`.

The Mac Docker engine remains unusable; successful container/Testcontainers/kind gates ran on GitHub's Linux runners. The CI source archive preserves documentation as it existed at the tested commit. Later documentation changes do not alter the verified archive.

## Phase summary

| Phase | Implemented and verified | External production gate |
|---|---|---|
| A | Scoped relational foundation, three state machines, asymmetric role checks, append-only history, reproducible host setup | Tenant/identity enrollment and official product terms |
| B | OpenAI/Anthropic adapters, schema/provenance validation, fixture evaluation and uncertainty handling | Live provider access, DPA/vendor/model approval, representative gold evaluation |
| C | Signed WhatsApp receipts, duplicates, encrypted persistence, media fallback, notification window/templates/opt-out previews | Meta account/template approvals, validated media scanning and real language/audio capabilities |
| D | Durable LangGraph recovery, text-evidence comparisons/resolutions, human corrections/rejections, risk flags, independent provider simulator and authenticated authoritative callbacks | Real OCR/ASR and verified Turaco contracts; production encrypted checkpoint/retention system |
| E | Human authorization, deterministic benefit, unique ledger, concurrency protection, UNKNOWN receipt-based reconciliation | Real payee/claimant verification and authorized provider disbursement contract |
| F | Local worker/retries/DLQ/replay, metrics, redacted trace adapter, security scans, SBOM, load smoke, backup/restore, CI, release packaging, Kubernetes rehearsal configuration | Chosen AWS account/region, staging approval and operational/country signoff |

## CI fix history (debugging record)

1. Nonexistent Trivy action reference → pinned to the verified commit SHA.
2. Action's default Trivy binary failed to install → pinned `v0.76.0`.
3. Floating Temurin base shipped Pebble with three HIGH Go findings → moved to `noble` Ubuntu LTS variants.
4. Keycloak kept stopping in kind → added probes and diagnostics, matched heap, raised memory limit. Exact root cause was not isolated; diagnostics did not confirm OOM.
5. Git line-ending filters made the verifier disagree with the source packager → compare archived files with raw blobs from the manifest commit, and retain the regression test.
6. Maven can rewrite its tracked Windows wrapper during release builds → restore only that wrapper with `git restore` (applying checkout filters), then retain the strict clean-worktree check.

## Not claimed

- Capacity: bounded synthetic load smoke is not capacity certification. The earlier run 15 measurement (40 reads, concurrency 4, 0 errors, P95 420 ms) is historical evidence, not a production target.
- Language quality: English/Swahili/mixed fixtures are not native-speaker accuracy certification.
- Security scanning: the Java dependency set and Java runtime image passed HIGH/CRITICAL scans; Python and npm audits also passed. Scanning did not cover every third-party image.
- Kubernetes: the kind rehearsal is not production security or network enforcement certification. The Keycloak dev fixture has a writable root by documented local-only exception.
- OCR, ASR and malware validation remain unsupported or human-assisted.
- No GitHub Release or production cloud deployment has been created. The live Codespaces
  workspace is a synthetic interview presentation only.

## Open production gates (external approval required)

- Real identity and payee enrollment
- Official policy and product terms
- Meta approval and message templates
- Representative language, model and media evaluation
- Approved OCR, ASR and malware scanning
- Vendor, privacy and country approvals
- Production encrypted checkpoint and retention
- Real provider and disbursement contracts
- AWS/EKS: chosen account, region and residency policy, reviewed identity, secrets and TLS, managed database, backup, operational approval

`APP_MODE=integration` fails closed. No real payments, patient data or Turaco connection.

## Current branch and presentation verification

- Current branch source: `53553fd9ba41a6162e7e3d41d82ee99621df3c65`
  (`codex/full-sdlc`). The 0.2.0 release artifact above remains tied to its original
  manifest commit.
- Cloud presentation CI run `38879723658` passed both `presentation` and `cloud-stack`.
- SDLC CI run `38879723651` passed Java, Python, Compose demo, dashboard and release-candidate
  jobs on the current branch.
- The dashboard update is deployed in Codespace `turaco-presentation-gateway-x74r9j5p572vvv7`.
  Port 5173 is public for the Cloudflare Worker; the other service ports remain private.
  Cloudflare `/demo/health` returned UP after deployment.
- The cloud end-to-end check passed inside the Codespace after deployment, including
  confirmation, assisted verification, human approval and UNKNOWN payout reconciliation.
- Dashboard action controls now explain that evidence references are required, display
  errors inline and refresh claim state after a 409 conflict. Local dashboard test and
  production build passed.
- A previous temporary port-visibility regression made the hub report a sleeping workspace;
  visibility was restored and documented in `docs/cloud-presentation.md`. Recheck it if the
  hub displays that message while Codespaces says the workspace is running.
- The release candidate was independently downloaded, checksum-verified and provenance-
  verified; its manifest source commit remains `24935a7ff5d69cb677d0444927970da8a0695218`.
  GitHub Actions reported non-blocking maintenance notices for older action runtimes and
  the upcoming ubuntu-latest image change; these did not fail any gate.
