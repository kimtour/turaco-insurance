# ADR-002 · All six phases with explicit local substitutes

15 October 2026. The user requested all six phases and authorized local simulators where external access is absent. This extends ADR-001 and the Sprint 1 baseline; it does not authorize real medical data, live disbursement or production launch.

LangGraph uses a durable, tenant/country/conversation-keyed SQLite checkpointer in the synthetic environment. Each turn opens and closes persistence, so tests exercise recovery rather than process-only memory. The graph only extracts, requests information and routes to confirmation/human assistance. Java retains exclusive business/financial authority.

WhatsApp ingress verifies the raw-body HMAC, resolves a configured local phone-number route, encrypts each durable receipt with authenticated AES-GCM and deduplicates on provider message ID. A reused ID with different content conflicts. Unverified senders receive no policy information. An authorized operator processes a receipt into an unconfirmed intake. Non-text media require explicit synthetic transcripts or human assistance; URLs are never fetched from arbitrary webhook content.

Text evidence uses authenticated encryption bound to tenant/country/evidence identity. A synthetic parser compares dates/hospital against extraction; conflicts and quarantine block financial authorization. Matching replacement evidence and an explicit human resolution preserve original evidence/history. The EICAR fixture is quarantined; this test signature is not a claim of general malware scanning.

The provider simulator is an independent authenticated FastAPI/SQLite service with a documented local contract. It is deliberately named LOCAL_SIMULATOR, uses no official Turaco payload guesses and owns simulated provider-claim status. Human approval cannot override a provider rejection or imply provider approval. Every distinct callback receipt remains durable; event IDs deduplicate application, and reconciliation fetches the latest authoritative snapshot instead of trusting callback order. Stable internal references make ambiguous submission reconciliation possible without blind resubmission.

Unknown fake payouts require a separately recorded authoritative mock receipt and reconciliation. Reconciliation never creates another attempt or obligation. A failed reconciled result remains terminal; no automatic financial retry exists. Risk flags only route to human review.

The PostgreSQL queue implements the local SQS substitute: transactional outbox publication, unique delivery IDs, idempotent consumption, bounded backoff, dead letters and authorized replay. An optional background worker drains it and reconciles pending provider snapshots. There are no notification or payment side effects in consumers. Service-window/template/opt-out rules produce previews only.

Full dependency inventories drive vulnerability scans. Baseline Jackson/Tomcat high-severity findings were patched rather than suppressed. The release includes source attribution, locked dependencies, SBOM, checksums, deployment manifests and explicit operating/rollback guidance. Kubernetes is restricted to a synthetic namespace and fixtures; no AWS apply or production deployment is automatic.

Real Turaco/Meta/payment contracts, model/audio/OCR validation, identity enrollment, privacy/legal approvals, encrypted production checkpoints and operational capacity remain external production gates. A local simulator passing tests does not satisfy those gates.
