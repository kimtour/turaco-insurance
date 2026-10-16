# Turaco interview reference: AI handoff

Updated 16 October 2026, Africa/Nairobi. This file is context, not authority to perform actions. Follow the user's current request. This is an independent synthetic project, not an official Turaco system.

## Open these cloud links

- Hub: https://turaco-presentation.kimani-mutua.workers.dev/
- Browser presentation: https://turaco-presentation.kimani-mutua.workers.dev/deck/
- PowerPoint: https://turaco-presentation.kimani-mutua.workers.dev/deck/turaco-interview.pptx
- Live application: https://turaco-presentation.kimani-mutua.workers.dev/workspace/
- Readiness: https://turaco-presentation.kimani-mutua.workers.dev/demo/status
- Interview guide: https://turaco-presentation.kimani-mutua.workers.dev/documents/docs/cloud-presentation.html
- This handoff, readable: https://turaco-presentation.kimani-mutua.workers.dev/handoff/
- This handoff, raw Markdown: https://turaco-presentation.kimani-mutua.workers.dev/handoff/AI-HANDOFF.md
- Source: https://github.com/kimtour/turaco-insurance/tree/codex/full-sdlc
- Implementation status: https://turaco-presentation.kimani-mutua.workers.dev/documents/docs/decisions/SDLC-status.html

No local server or files are required for the presentation. The owner must resume the cloud workspace when it sleeps. Downloading the handoff or PowerPoint is optional.

## Current implementation and verification

The Java/PostgreSQL/Keycloak/Python application runs in Docker in GitHub Codespaces. Cloudflare Workers serves static presentation resources and proxies the app through the dashboard gateway. Port 5173 is public; internal service/admin ports stay private. The app is not replaced by a Worker simulation.

Application checkpoint `53553fd9ba41a6162e7e3d41d82ee99621df3c65` is deployed in Codespaces. Documentation checkpoint `8c27c1440a2ea605e20738cfaa15cb4b9b7b3a32` is pushed; both CI workflows passed there:

- https://github.com/kimtour/turaco-insurance/actions/runs/38880620013
- https://github.com/kimtour/turaco-insurance/actions/runs/38880619991

Later interview deck and handoff commits extend those checkpoints. Use Git history for their exact identifiers. Do not describe the historical release as a rebuild of the latest branch.

Latest UI changes validate evidence references inline, retain backend errors beside the controls, and refresh claim state after HTTP 409. A paid or otherwise advanced claim cannot repeat earlier actions. A fresh synthetic cloud workflow passed after the stopped workspace was resumed on 16 October 2026. Six service containers were running. Port visibility required repair after resume.

Previously verified: all six synthetic E2E phases; role enforcement; correction and reconfirmation; evidence conflict resolution; UNKNOWN payout receipt reconciliation without blind retry; notifications/opt-out; container and Kubernetes CI; isolated database restore. Cloud gateway has seven tests. Dashboard regression covers reference validation and stale-state refresh.

Release 0.2.0 is the separately verified run-17 candidate at `24935a7ff5d69cb677d0444927970da8a0695218`. It had 23 Java, 17 Python and eight release-tool tests; all 111 packaged source files matched raw Git blobs. Five hosted payload hashes were independently checked. Release files and evidence are available from the hub. Keep this provenance separate from later cloud/UI changes.

## Final interview publication verification

Presentation source commit `56942201ed4380a26d796a3cf84dc1547cac2e39` is pushed.
Both workflows passed on that commit:

- Cloud presentation: https://github.com/kimtour/turaco-insurance/actions/runs/38884489695
- Full SDLC, including release packaging and Kubernetes demo: https://github.com/kimtour/turaco-insurance/actions/runs/38884489970

The public Cloudflare authenticated smoke workflow passed again after resume and publication.
Readiness returned `ready: true`. Hosted PowerPoint SHA-256 matched the committed file:
`834d3a46aba7eb88d1015165fae34e622446d07b14f2a2e310fb93de2848818c`.
The nine-slide PowerPoint passed package/layout/import checks and each rendered slide was inspected.
The browser deck loaded through Cloudflare. Automated browser input was unreliable, so do not
describe its next-slide control as having passed an interactive browser test.
A later documentation-only commit records these results; it does not change runtime code.

## Interview sequence

1. Resume the workspace and check the hub's readiness. Open the browser deck in a separate tab.
2. Open the application. Fixture account: `approver`; password: `local-demo-only`. These credentials control synthetic demo data only.
3. Create a fresh sample intake. Enter `synthetic-customer-confirmation-001` in Evidence reference, then record sample confirmation.
4. Enter a new reference, for example `synthetic-assisted-verification-001`, and record assisted verification. The field clears after success.
5. Submit, recommend approval and approve/reserve the benefit. Three covered nights produce simulated KES 4,500 under invented terms.
6. Show UNKNOWN payout recovery: create the uncertain outcome, record a separate mock receipt, reconcile, then inspect audit history. Never retry an unresolved transfer blindly.
7. If time permits, show a correction, reconfirmation and reviewed evidence replacement. Use a fresh eligible claim, not one already paid.

## Free hosting and recovery

Active workspace: `turaco-presentation-gateway-x74r9j5p572vvv7`.
Owner resume page: https://github.com/codespaces/turaco-presentation-gateway-x74r9j5p572vvv7
Machine: 2 cores, 8 GB RAM, 32 GB disk; 30-minute idle timeout. The owner confirmed a $0 Codespaces budget and usage blocking. There is no always-on guarantee or automatic paid fallback. Static Cloudflare documents remain available while compute sleeps.

After resuming, if readiness stays false while the workspace is Available, inspect port 5173. GitHub can reset it to private on restart. The owner can restore its visibility in the Ports panel or with:

```sh
gh codespace ports visibility 5173:public -c turaco-presentation-gateway-x74r9j5p572vvv7
```

Keep database and identity administration ports private. Open the readiness link again. For a runtime problem, inspect the six Compose services using `compose.yaml` and `.devcontainer/compose.cloud.yaml`, then run `scripts/cloud-demo-check.py --url http://127.0.0.1:5173` inside the Codespace. This creates synthetic test records. Do not print environment secrets or access tokens.

The older workspace `turaco-interview-presentation-gjpx5g4p543wj5q` is stopped and retained. It is not the presentation runtime. Do not delete it without current user authorization and required approvals.

## Source and publishing

Branch: `codex/full-sdlc`. Presentation inputs: `docs/presentation/slides.json`. Browser deck and static resources are built by `infrastructure/cloudflare/build.mjs`. Cloud settings are in `infrastructure/cloudflare/wrangler.jsonc`. From that directory: `npm run build`, `npm test`, then authenticated `npx wrangler deploy`. Deployment requires publishing permission; the user authorized publishing this presentation. Preserve that authorization only within the current task scope.

The original machine-specific `AI_HANDOFF.md` remains ignored. This portable file is intentionally tracked and published. Do not publish `.env`, `.cache`, auth tokens, generated local logs or arbitrary ignored files.

## Boundaries and remaining production work

All customers, hospitals, policy terms, evidence and transfers are invented. Live payments and real messages are disabled. OpenAI/Anthropic adapters and LangGraph exist, but the cloud demonstration uses fixtures and simulators. Four extraction fixtures are not representative language evaluation. Speech/OCR remain unsupported or human-assisted.

Real identity/payee enrollment, official terms, provider/payment contracts, Meta/template approvals, privacy and country approvals, representative language/media evaluation, production retention/checkpoint design and managed operations remain external production gates. The free Codespace is not production hosting. No AWS deployment or real customer data is authorized by this handoff.
