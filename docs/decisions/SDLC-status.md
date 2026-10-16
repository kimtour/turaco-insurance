# SDLC status

Independent synthetic reference implementation. Not an official Turaco system.

## Verified source

- Repository: https://github.com/kimtour/turaco-insurance (public)
- Branch: `codex/full-sdlc`
- Tested commit: `83260ee865f100150ecf18d2ef2b6010bc1d29ea`
- Application version: 0.2.0
- CI run 15: https://github.com/kimtour/turaco-insurance/actions/runs/38849541989
- All five jobs passed for the tested commit. Run 14 also passed on its parent
  source-provenance fix; run 15 additionally verified the filtered Maven-wrapper restore.

## Remote CI result (run 38849541989)

| Job | Result | Scope |
|---|---|---|
| java | Passed | PostgreSQL Testcontainers tests, SBOM, HIGH/CRITICAL dependency scan |
| python | Passed | Locked install, tests, pip-audit |
| dashboard | Passed | Production build, npm audit |
| compose-demo | Passed | Image builds, authenticated demos, recovery workflow, load smoke, API export, Java application-image scan, kind deployment and authenticated Kubernetes demos |
| release-candidate | Passed | Eight release-tool tests, build, packaging, provenance verification and artifact upload |

## Artifacts (non-expired at 2026-10-16)

- `java-verification`: ID 12024885073
- `generated-api-contracts`: ID 12023774457
- `synthetic-release-candidate-0.2.0`: ID 12025090375, expires 2027-01-14
- Release artifact ZIP SHA-256: `31b0a6c87a72e4f5c5368db75c9771a20e3d637946f0a54832aff10a5ed576d7`

The artifact was downloaded and verified locally on 2026-10-16. Its ZIP digest
matched GitHub's artifact digest and all five payload checksums passed. The
manifest identifies commit `83260ee865f100150ecf18d2ef2b6010bc1d29ea` / version
`0.2.0`. All 108 source files match that commit's raw Git blobs byte for byte;
the dashboard archive has three files and the SBOM has 100 components. Full
payload hashes and verification scope are recorded in
[RELEASE-0.2.0-run-15-verification.json](RELEASE-0.2.0-run-15-verification.json).

## Local release package

`release/0.2.0/` contains the independently verified run 15 artifact for commit
`83260ee865f100150ecf18d2ef2b6010bc1d29ea`. The previous local candidate for
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

- Capacity: the load smoke (40 reads, concurrency 4, 0 errors, P95 420 ms) is not capacity certification.
- Language quality: English/Swahili/mixed fixtures are not native-speaker accuracy certification.
- Security scanning: the Java dependency set and Java runtime image passed HIGH/CRITICAL scans; Python and npm audits also passed. Scanning did not cover every third-party image.
- Kubernetes: the kind rehearsal is not production security or network enforcement certification. The Keycloak dev fixture has a writable root by documented local-only exception.
- OCR, ASR and malware validation remain unsupported or human-assisted.
- No GitHub release or cloud deployment has been created.

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

## Current-head revalidation

Run 15 passed all five jobs on commit `83260ee865f100150ecf18d2ef2b6010bc1d29ea`.
The release candidate was independently downloaded, checksum-verified and provenance-
verified; its manifest source commit matches the tested commit. Only documentation
updates follow this tested code/workflow commit.