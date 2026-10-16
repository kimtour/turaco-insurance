# Cloud presentation guide

Independent synthetic insurance reference. No real customers, clinical documents, notifications or transfers.

## Your presentation links

The Cloudflare presentation hub provides the live workspace, this guide, architecture, source, verification evidence, API contracts, release downloads and an AI handoff. The live workspace runs the existing Java/PostgreSQL/Keycloak/Python application in GitHub Codespaces using Docker Compose. Cloudflare serves the documents and proxies the workspace over HTTPS. No presenter laptop process is required.

## Before the interview

1. Open **Start cloud workspace** from the hub and sign into GitHub as its owner. Resume the existing Codespace; do not create another for every presentation.
2. Allow startup to complete, then use **Check demo status** in the hub. Green means the API is responding through the cloud gateway. If the hub still reports a sleeping workspace while the Codespace is running, its dashboard port 5173 must be public for Cloudflare to reach it.
3. Open **Live claims workspace**, choose Approver, and use the published fixture password `local-demo-only`. These accounts only control invented demonstration data.
4. Keep the Codespace browser tab open during the presentation. A sleeping workspace must be resumed from GitHub. The hub, source and evidence remain accessible while compute sleeps.
5. Stop the Codespace after the presentation from GitHub's Codespaces page. Its database volumes remain in that Codespace until it is deleted or expires.

## Suggested 8-minute story

**Problem · 1 minute.** Help a customer submit a hospitalization claim while preserving confirmation, human authority, auditability and payment safety. The product terms, hospital and identities in this demo are invented.

**Architecture · 1 minute.** React → Java API → PostgreSQL, with Keycloak roles. Python handles schema-validated extraction and durable conversations. Independent local simulators stand in for providers, channels, payouts and queue delivery. Cloudflare fronts the presentation; GitHub hosts the actual compute.

**Happy path · 3 minutes.** Create a sample intake. For each action, enter a synthetic evidence reference in the required field; the field clears after a successful action. Record customer confirmation, then assisted identity/payee verification. Submit, recommend approval, approve/reserve the deterministic benefit, then simulate a confirmed payout. Three covered nights produce KES 4,500 under the invented policy. Show the audit trail.

**Failure and recovery · 2 minutes.** On a second approved claim, expand recovery tools and simulate an UNKNOWN payout. Another attempt is blocked. Record a separate mock provider receipt, then reconcile. For evidence review, attach conflicting synthetic text, attach matching text, and record the reviewed replacement before approval. Corrections reopen confirmation.

**Engineering evidence · 1 minute.** Open CI evidence and release provenance. Explain duplicate/concurrency protection, role and scope checks, encrypted evidence, outbox/replay, fixture evaluation and the distinction between synthetic tests and production approval.

## Questions worth preparing for

- Why SDK adapters plus LangGraph? Provider adapters isolate extraction; a durable workflow preserves conversation and escalation state. Model output never authorizes a payout.
- What prevents duplicate transfers? A unique obligation, stable attempt identity, transactional state guards and receipt-based UNKNOWN reconciliation.
- What is actually deployed? The existing reference application, with a same-origin cloud gateway; not a replacement JavaScript simulation.
- What is still external? Approved real identity/payee enrollment, official product terms, Meta/templates, representative model/language/media evaluation, vendor/privacy/country approvals, production retention and real provider/payment contracts.
- Is this production? No. Keycloak demo users and password grants, a single cloud workspace and simulated integrations are presentation fixtures.

## Free-service limits

Cloudflare Workers Free serves the hub and gateway within its daily quotas. GitHub personal accounts include a monthly Codespaces compute/storage allowance. This is limited free allowance, not unlimited always-on hosting. Keep a zero-spend budget with usage blocking enabled in GitHub billing; no paid plan is required by this deployment. Stop idle workspaces and monitor storage as well as compute. The deployment does not change billing plans or remove spending limits.

The Codespace base image includes Python for startup scripts. The cloud Compose override uses host networking for Python image builds: a wheel download succeeded from the workspace and a host-network container but timed out during TLS negotiation through the nested Docker bridge. Runtime service networking remains isolated. Services restart with the Docker daemon when the workspace resumes.

The Cloudflare gateway sets the [documented development-tunnel header](https://learn.microsoft.com/en-us/azure/developer/dev-tunnels/security) for its configured Codespace so browser requests reach the app rather than a development-port notice. The dashboard's 5173 port is public so Cloudflare's Worker can reach it; Nginx exposes the workspace and its token-authenticated API routes, not the database, Keycloak administration, Python or provider simulator ports. The Codespace URL is therefore also reachable directly. Use the Cloudflare hub for the presentation, and remember that the public fixture credentials control synthetic data only.

Only the dashboard gateway is forwarded. PostgreSQL, Keycloak administration, Python and simulator ports remain private and are not exposed as presentation links. No private environment files, generated service secrets, database backups or access tokens are published. Public reference source, synthetic screenshots and verified release binaries are the presentation artifacts.

## Cloud operator handoff

- Source branch: `codex/full-sdlc` in `kimtour/turaco-insurance`.
- Cloudflare configuration: `infrastructure/cloudflare/`; assets are generated from an explicit repository-document and verified-release allowlist.
- Cloud workspace configuration: `.devcontainer/`; startup builds/resumes the Docker stack, checks the same-origin gateway and seeds one unconfirmed fixture if the database is empty.
- Cloud smoke: `scripts/cloud-demo-check.py` tests authentication, anonymous denial, intake/confirmation/review/approval and UNKNOWN recovery through one HTTPS URL, without printing tokens.
- Cloudflare configuration allows only an HTTPS `*.app.github.dev` upstream. Secrets and identity-administration routes are not proxied.
- The original Java/Python verification evidence remains tied to its named source commit; cloud deployment evidence is separate. Later documentation does not change the verified release archive.
- Rebuilding/deleting a Codespace may reset runtime state. All durable presentation documents and source remain in Cloudflare/GitHub.
