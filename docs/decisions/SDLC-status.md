# SDLC status

Independent synthetic reference implementation. Not an official Turaco system.

## Verified source

- Repository: https://github.com/kimtour/turaco-insurance (public)
- Branch: `codex/full-sdlc`
- Tested commit: `57e361ea0ea9d27e7c0730eb2f6c20cdc17b1a20`
- Application version: 0.2.0
- CI run: https://github.com/kimtour/turaco-insurance/actions/runs/38791009127
- Documentation commits made after the tested commit are recorded below and were not
  themselves run through the full gate unless stated.

## Remote CI result (run 38791009127)

| Job | Result | Scope |
|---|---|---|
| java | Passed | PostgreSQL Testcontainers tests, SBOM, HIGH/CRITICAL dependency scan |
| python | Passed | Locked install, tests, pip-audit |
| dashboard | Passed | Production build, npm audit |
| compose-demo | Passed | Image builds, authenticated demos, full-platform recovery workflow, load smoke, API export, Java application-image scan, kind deployment, authenticated Kubernetes demos |
| release-candidate | Passed | Build, packaging, artifact upload |

## Artifacts (non-expired at 2026-10-15)

- `java-verification`: ID 12000651361
- `generated-api-contracts`: ID 12000196781
- `synthetic-release-candidate-0.2.0`: ID 11999099722

Release artifact checksum verification: PASS, 2026-10-15, Codex (independent artifact inspection via GitHub API; all five listed files verified, none missing).
Manifest commit/version: `57e361ea0ea9d27e7c0730eb2f6c20cdc17b1a20` / `0.2.0`.

All 103 archived source files match the tested commit byte for byte. Source/UI archive contents were checked for unsafe and excluded runtime paths, and known generated local secret values were checked without printing them. These checks do not prove absence of every possible credential. The placeholder `.env.example` and documented synthetic fixture credentials are expected. Exact hashes, verification time and scope limits are recorded in [RELEASE-0.2.0-verification.json](RELEASE-0.2.0-verification.json).

## Local release package

`release/0.2.0/` now contains an unchanged copy of the independently verified CI artifact for
`57e361ea0ea9d27e7c0730eb2f6c20cdc17b1a20`, including its original manifest and checksums.
`docs/decisions/RELEASE-0.2.0-verification.json` records this current artifact's verification.
The earlier local package, screenshot and `verification.json` for
`1615427bf24152389798baaefd385b8de06ac7d9` were preserved under ignored
`.cache/verification/superseded-release-1615427/` and labeled with `SUPERSEDED.md`.
The original host measurements remain evidence for that earlier package.

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

## Documentation-only commits (not re-tested)

- `1b4cea7a039c4c5ac04b04016b06db3889ed122b` — status and independently verified artifact evidence; documentation only, not re-tested. Subsequent documentation commit identities are recorded in Git history. Runtime evidence remains tied to `57e361e`.
