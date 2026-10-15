# API behavior

OpenAPI snapshots are in `docs/openapi/`. FastAPI also exposes `/openapi.json` and `/docs`. These internal contracts do not describe or impersonate the Turaco API.

Insurance endpoints require a JWT with configured issuer, `insurance-api` audience, tenant/country claims and the appropriate realm role. Scope comes from the token. Unscoped requests fail with 403; cross-scope resources return 404. Lists accept `page=0&size=20`, capped at 100, and return arrays.

| Path under `/api/v1` | Role | Behavior |
|---|---|---|
| `GET /catalog` | claims:read | Mode, products, incident codes, simulated-only flag |
| `POST /customers` | claims:write | Scoped synthetic customer |
| `POST /policies` | claims:write | Active synthetic Hospital Cash policy |
| `GET /policies`, `GET /policies/{id}` | claims:read | Paginated list or scoped policy |
| `POST /policies/{id}/cancellations` | claims:approve | Cancellation with history, no deletion |
| `POST /claims` | claims:write | Unconfirmed intake; Idempotency-Key required |
| `GET /claims`, `GET /claims/{id}` | claims:read | Paginated queue or full claim |
| `POST /claims/{id}/confirmations` | claims:write | Explicit critical-field confirmation with evidence reference |
| `POST /claims/{id}/verifications` | claims:review | Staff-attested assisted identity/payee verification |
| `POST /claims/{id}/submissions` | claims:write | Requires identity, confirmed critical fields and eligibility |
| `POST /claims/{id}/reviews` | claims:review | Recommendation, reason code, evidence reference |
| `POST /claims/{id}/approvals` | claims:approve | Latest review recommends approval; verify payee, recalculate and reserve |
| `POST /claims/{id}/simulated-payouts` | claims:pay | Fake CONFIRMED or UNKNOWN result; no transfer |
| `GET /claims/{id}/audit` | claims:read | Actor/time/details/correlation history |

Creation returns 201 for first creation and identical replay. Keys are scoped to tenant/country and accept 1–120 ASCII letters/digits/colon/underscore/hyphen. Different input under the same key returns 409. Incoming confirmed fields are downgraded to extracted; unverified values stay unverified. A local intake is not a submitted or approved insurance claim.

Confirmation fields must be exactly `incident_type_code`, `hospital_name`, `admission_date`, `discharge_date`. Critical values need provenance. Unknown incident codes are rejected. Model hospital nights stay unverified; Java recomputes them. Conflicting/missing data blocks the workflow. Version 0.2 adds staff corrections and encrypted synthetic-text evidence. Binary documents and claimant self-service remain external or later scope.

Verification is a synthetic staff attestation with `evidence_reference` and `payee_verified`, not an identity integration. Policy-number knowledge or WhatsApp phone possession is never automatically treated as identity proof. The demo records synthetic human steps explicitly; AI has no such credentials.

Approval takes `evidence_reference`; reason/evidence are also recorded in the required preceding review. Approved payee verification cannot change. Benefit amount, currency and obligation ID are returned with the claim. UNKNOWN payouts cannot be retried. Version 0.2 adds receipt-based reconciliation; it preserves the original attempt and never fabricates provider confirmation.

An information request must be reconfirmed and resubmitted before a new recommendation or approval. Review changes are blocked once a payout obligation is authorized, including for provider-simulated claims. New text-intake requests cache the original text/policy/language fingerprint: concurrent or later identical retries return the existing claim without another extraction call, including during an extraction-service outage. A changed request under that key returns 409. Older text intakes without a cached text fingerprint retain the original extraction-based replay check until a matching successful retry establishes it.

Business/validation errors use `code`, `detail`, `correlation_id`; authentication and routing errors may use standard Spring responses. Every response includes `X-Correlation-ID`, accepting a client UUID. Request bodies, quoted evidence and tokens are not deliberately written to ordinary logs.

Extraction `/v1/*` requires `Authorization: Bearer <AI_SERVICE_TOKEN>`. `/v1/extract-claim` accepts transcript (maximum 20,000 characters), language en-KE/sw-KE, and optional incident catalog `["HOSPITALIZATION"]`. Unsupported providers/languages fail explicitly. Capabilities label text as synthetic fixtures only and ASR as disabled. The current transcription route explicitly rejects unsupported speech; no validated ASR result is claimed.

## Version 0.2 extensions

The generated insurance contract is exported from Springdoc on the running implementation, rather than manually approximated. New routes cover text intake, durable conversation turns, corrections, evidence and resolutions, risk assessments, rejection, provider submission/reconciliation, fake receipt/payment reconciliation, notification previews, channel processing and queue operations. See `PlatformController`, `OperationsController` and the generated contract for exact body schemas.

Provider callbacks use `/webhooks/turaco-simulator` with a dedicated bearer secret; they do not use staff JWTs. This local contract supplies explicit event IDs and revisions. Every receipt remains durable, duplicates do not reapply state, and current provider snapshots determine status. This endpoint is not a claim that official Turaco supports the simulator payload.

WhatsApp challenge and POST routes are publicly reachable only for their provider authentication checks. POST verifies X-Hub-Signature-256 against raw bytes before parsing and persists encrypted receipts before acknowledgment. The configured local phone-number route is SYNTHETIC_NUMBER; no tenant is accepted from an unauthenticated header. Processing a receipt requires a scoped staff token and produces only an unconfirmed intake. Non-text content requires explicit synthetic transcript input or human assistance.

Evidence is synthetic text, AES-256-GCM encrypted and bound to tenant/country/evidence ID. Lists expose metadata only. MATCH is produced by the local parser, not validated OCR. Conflicting/unverified/quarantined evidence blocks reservation; reviewed matching replacements preserve originals. UNKNOWN payouts require a separately recorded mock-provider receipt before reconciliation. No receipt endpoint or trace can authorize real disbursement.

Evidence checks are also bound to a digest of the critical claim facts. After a correction changes those facts, earlier MATCH checks and replacements cannot authorize reservation. Record current matching evidence and a fresh human resolution for each stale item. Resolutions append history; prior resolutions remain immutable. Evidence created before this binding was introduced has no proven snapshot and requires a current check/replacement before any new reservation. Existing obligations and payout attempts are preserved.

`/v1/conversations/turn` persists LangGraph state across calls with tenant/country/conversation scope. `/v1/verify-document` explicitly identifies its synthetic parser. `/v1/transcribe` rejects unsupported ASR. Notification routes return previews only and honor window/template/opt-out logic. Operations expose scoped counters, bounded outbox processing, dead-letter lists and authorized replay; all mutations retain audit references.
