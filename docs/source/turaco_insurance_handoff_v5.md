# Turaco Insurance, AI Architecture and Implementation Handoff v5

**Project:** Turaco Insurance (independent reference implementation, not an official Turaco system)
**Date:** 15 October 2026
**Supersedes:** v4. This is the implementation contract for Sprint 1.
**Scope:** Multilingual WhatsApp insurance claims intake, AI extraction, policy verification, human review, and simulated payout workflows. First product: **Hospital Cash**.
**Source baseline:** https://github.com/kimtour/insurance-policy-api (source files inspected, see section 12) and https://docs.turaco.insure/reference/getting-started

---

## 0. Changes from v4

| # | Decision or correction | Where |
|---|---|---|
| 1 | Separate intake, provider claim, and payout state machines, with independent ownership | 2 |
| 2 | Explicit synchronization conflict rules and safe provider submission behavior | 2, 6 |
| 3 | Require idempotent payout obligations from Sprint 1; simulated payments only | 11, 16 |
| 4 | Layered identity verification; knowledge questions do not count as strong proof | 8 |
| 5 | Webhook authentication minimum and deduplication/reconciliation design | 6 |
| 6 | Source-level repo audit with concrete findings; version compatibility remains to be tested | 12 |
| 7 | Introduce an executable Sprint 1 verification contract and blocked external dependencies | 16, 17 |

---

## 1. Executive decision

Use Java 25 / Spring Boot, PostgreSQL, React, AWS and Kubernetes for insurance operations. Build a separate Python FastAPI AI service with Pydantic validation. Start with a direct OpenAI SDK or Anthropic SDK adapter for structured extraction (one default, one behind a feature flag), benchmark on task-specific multilingual datasets, and add LangGraph when persistent multi-turn conversations are introduced. Use Langfuse and reproducible evaluation datasets for AI observability. Never use an unrestricted autonomous agent for claims approval or payout authorization.

**Roles**
- **OpenAI SDK / Anthropic SDK:** model access, structured extraction, tool calls.
- **LangGraph:** conversational state, follow-up questions, confirmations, human handoff, checkpointing. Never moves money.
- **Spring Boot:** workflow state, eligibility, approvals, audit, payouts. System of record in Standalone mode only (see section 2).
- **Turaco API:** system of record for customers, products, policies and claim status in Integration mode.

**MVP scope:** Hospital Cash claims; English and Swahili text first; synthetic customers; simulated payments.

**Out of scope for MVP:** Hermes Agent (internal engineering evaluation only), Tasklet, Temporal / Step Functions (adopt only if outbox and workers prove insufficient), voice replies (TTS), fine-tuned ASR.

---

## 2. Operating modes and source of truth

The system is built behind ports. Domain code depends on the ports; one adapter set per mode implements them.

```text
PolicyPort        findPolicy, listBenefits, checkActive(policy, date)
CustomerPort      findCustomer, createCustomer
ProductCatalogPort   listProducts, listIncidentTypes, listBenefits(policy)
ClaimSubmissionPort  submitClaim, uploadDocument, getClaimStatus
```

Adapters: `StandaloneAdapter` (local tables, synthetic data) and `TuracoAdapter` (documented HTTP API, test base URL by config).

| Entity / data | Standalone mode | Integration mode |
|---|---|---|
| Customer | Local, system of record | **Turaco** is SoR; local row is a linked cache with `turaco_customer_id` |
| Product, benefits, incident types | Local seed data | **Turaco** is SoR; fetched and cached with TTL |
| Policy | Local, SoR | **Turaco** is SoR; local cache stores both `turaco_policy_id` and `policy_number` |
| Claim (official status) | Local, SoR | **Turaco** is SoR; local claim stores `turaco_claim_id` and mirrors status from webhooks |
| Intake record (conversation, media, transcript, extraction, confirmations) | Local | Local (Turaco has no equivalent) |
| Human review, risk assessment, audit events | Local | Local |
| Payout obligation and attempts | Local simulated ledger | Local ledger; real disbursement contract **unconfirmed** (see section 6) |

**Rule:** in Integration mode, never mutate cached Turaco-owned data except through the adapter; overwrite the cache from Turaco responses and webhooks.

**Three orthogonal state machines (never collapse into a single `claim.status`):**

1. `IntakeStatus` (owned locally, both modes): `OPEN -> NEEDS_CONFIRMATION -> VERIFIED -> READY_TO_SUBMIT -> SUBMITTED | CANCELLED`. If an item needs correction, transition to `NEEDS_CONFIRMATION`; retain prior versions of extracted values.
2. `ProviderClaimStatus` (authority differs by mode): `NOT_SUBMITTED -> SUBMISSION_PENDING -> RECEIVED -> PROCESSING -> APPROVED | REJECTED | CLOSED`, plus `SYNC_UNKNOWN` for missing/ambiguous provider state. In standalone mode a local claims decision service is authoritative. In Turaco Integration mode, **Turaco's returned state is authoritative**, and local reviewer recommendations cannot be represented as provider approval.
3. `PayoutStatus` (local obligation ledger only, no live payouts in MVP): `NOT_AUTHORIZED -> RESERVED -> SUBMITTED -> CONFIRMED | FAILED | UNKNOWN`. A payout can be reserved only after an authoritative approval, verified payee, and deterministic benefit calculation. `UNKNOWN` requires reconciliation before retry.

Every state transition records a durable append-only event with actor, timestamp, source, policy version, and correlation ID. Claims table stores separate `intake_status`, `provider_claim_status`, and `payout_status` snapshots for querying.

**Reconciliation and conflicts:** In Integration mode, submitted claims retain both local intake and external IDs. Turaco API responses and verified webhook snapshots update provider state. A stale event must not overwrite a newer authoritative snapshot. Prefer a provider revision/updated timestamp if documented; otherwise refetch the provider claim and reconcile against the latest authoritative response. Out-of-order or ambiguous events are retained as evidence and flagged `SYNC_UNKNOWN`, not mapped by guessed precedence. A local reviewer cannot force a Turaco claim from REJECTED to APPROVED. Avoid propagating any local simulated payout as a real provider payout.
---

## 3. Services and deployment units

- `insurance-api` (Spring Boot): domain, ports/adapters, claims workflow, payouts, audit, and the **WhatsApp gateway as a module** (package `channel.whatsapp`). Split out later only if scaling or team boundaries demand it.
- `ai-service` (FastAPI): ASR router, language registry, extraction, LangGraph workflow.
- `claims-dashboard` (React): review queue, evidence view, approvals, audit trail.
- **Queue vs outbox:** Postgres outbox is the source of truth for events produced by `insurance-api` (written in the same transaction as the state change). A relay publishes outbox rows to **AWS SQS** for async consumers (media worker, AI service calls, notifications). Consumers are idempotent by event ID. Dead-letter queues are required.

---

## 4. Business requirements and constraints

1. Support Kenya, Uganda, Nigeria, Ghana and Zambia, starting with approved language-country pairs.
2. Receive WhatsApp text, voice notes, images and documents.
3. Identify or ask for language preference. Country and phone code are hints, not proof.
4. Transcribe voice only in validated languages; preserve original audio and source transcript securely.
5. Extract claim data with per-field provenance; require customer confirmation before core decisions.
6. Policy and benefit checks happen in deterministic Java services.
7. Escalate uncertain or suspicious claims to authorized human review.
8. Idempotent message handling, claim creation, payout submission and callbacks.
9. Standalone mode uses synthetic customers and mock providers.
10. Integration mode requires authorized credentials and confirmed contracts.

### Language support

| Market | Initial target languages | Subsequent candidates |
|---|---|---|
| Kenya | English, Swahili | Kikuyu, Dholuo, Kamba, Luhya, Kalenjin |
| Uganda | English, Luganda, Swahili | Runyankole, Acholi, Ateso, Lusoga |
| Nigeria | English, Nigerian Pidgin, Hausa, Yoruba, Igbo | Fulfulde, Kanuri, Efik |
| Ghana | English, Akan/Twi, Ghanaian Pidgin | Ewe, Ga, Dagbani |
| Zambia | English, Bemba, Nyanja | Tonga, Lozi, Kaonde, Lunda |

Target inclusion is a requirement, **not** a claim that ASR, translation or TTS is validated. Implement a capability registry keyed by `(country, language, modality)` with modalities `text_understanding`, `asr`, `translation`, `tts`, plus evaluation version, quality gates and a human-assisted fallback.

**Code-switching:** real messages mix languages (Swahili/English/Sheng, Pidgin/English, Luganda/English). The eval dataset must include code-switched samples and report accuracy on them separately.

---

## 5. End-to-end processing

1. **Webhook.** Meta WhatsApp webhook hits the gateway module. Verify signature, reject invalid, deduplicate on provider message ID, persist the inbound event durably, acknowledge fast.
2. **Media worker.** Fetch media promptly (Meta media URLs are short-lived), validate type/size, malware-scan, store encrypted originals with retention metadata, normalize audio with FFmpeg.
3. **Language.** Use stored preference or validated language ID; ask the customer when confidence is low.
4. **ASR.** Router picks a validated model for the country-language pair; unsupported pairs go to human-assisted handling. Keep original audio and transcript.
5. **Extraction.** LLM fills the versioned schema (section 9). Unknowns stay null; evidence spans and provenance are mandatory; nothing is fabricated.
6. **Conversation.** LangGraph (when enabled) requests missing fields and confirmations, escalates uncertainty. State is keyed by authenticated customer and claim IDs.
7. **Claimant verification** (section 8) before any policy detail is disclosed or claim created.
8. **Eligibility.** Java service checks coverage on the incident date, waiting periods, exclusions, benefit limits.
9. **Documents.** OCR extracts evidence data; discrepancies route to review.
10. **Risk.** Rules (and later a validated model) score risk. Scores route to review; they never auto-reject or auto-approve.
11. **Approval.** Authorized reviewer records decision with reason, actor, time, rule version, evidence references.
12. **Provider submission** (Integration mode). Submit claim and documents through `ClaimSubmissionPort`; track status from webhooks.
13. **Payout.** Calculate benefit, reserve a unique payout obligation, submit via mock or authorized provider. Reconcile ambiguous outcomes before any retry.
14. **Notification.** Send localized, approved messages. Report payment completion only on provider confirmation.
15. **Tracing.** Correlation IDs, privacy-filtered events, dashboards, alerts, regression suites.

### WhatsApp constraints (design for these)

- Free-form replies are allowed only inside the 24-hour customer-service window after the customer's last message. Proactive status updates outside it require pre-approved **message templates**.
- Templates are approved per language; build a template registry keyed by `(language, template_key, version)` and fall back to English or human contact where none exists.
- Media URLs expire; download immediately and store your own copy.
- Webhooks can be duplicated or reordered; message IDs are the dedup key.
- Support opt-out and record consent.

---

## 6. Turaco integration module (Integration mode)

**Package:** `integration.turaco` (Java), versioned client, no domain logic.

**Documented (from https://docs.turaco.insure, reviewed 15 Oct 2026):**
- Bearer-token authentication; test base `https://api-test.turaco.insure/api/v1/`.
- Create Customer (`POST /customers`): `fullName`, `mobileNumber` required; `gender`, `dateOfBirth`, `age`, `email`, `idNumber`, `customerIdentifier`, `documentType`, `externalRef`, `metadataEntries` optional. Responses 201, 400, 401, 403.
- Resources documented: products and product packs, policies, premium payment notification, claims, claim documents, incident types, policy benefits, webhook configuration.
- Webhook envelope: `{ "entity": CLAIM|POLICY|PAYMENT|CUSTOMER, "action": CREATE|UPDATE|DELETE|APPROVE|REJECT|PROCESSING|PAYMENT|CLOSE, "payload": <entity object> }`. Auth modes: NONE, BEARER, BASIC.

**Not established, confirm before coding (do not assume):**
- Whether any Turaco API disburses **claim payouts**. The Payments API notifies Turaco of *premium* payments.
- Webhook ordering, retry schedule and delivery guarantees.
- The incident-types endpoint linked from the sample use case returned 404, and one older benefits reference uses a different test hostname. Obtain the current OpenAPI spec and base URLs first.
- Exact claim and policy payload shapes per entity.

**Identifier mapping (table `external_ref`):**

| Internal | External | Notes |
|---|---|---|
| `customer.id` (UUID) | `turaco_customer_id`, `externalRef` | Use `externalRef` to carry our UUID |
| `policy.id` (UUID) | `turaco_policy_id` (API resource id), `policy_number` (customer-facing) | Never treat these as interchangeable; store both |
| `claim.id` (UUID) | `turaco_claim_id` | Set after provider submission |

**Webhook consumer rules:**
1. Require BEARER or BASIC authentication over TLS with secrets in the vault, plus network controls where possible. `NONE` is documented as an option but **is disallowed for production** absent a separately approved and independently authenticated ingress mechanism. Prefer stronger signed messages if Turaco supplies them; do not assume a signature contract exists.
2. Persist each delivery with receipt UUID, verified auth context, received timestamp, entity/action, external object ID, sanitized payload reference, and canonical body digest, then acknowledge quickly.
3. A body hash helps deduplicate identical re-deliveries, but is **not a provider event ID** and cannot establish event ordering. Enforce a unique constraint on a documented delivery ID if available; otherwise make entity reconciliation and state application idempotent, preserving distinct receipts for audit.
4. Never infer latest state from webhook arrival order. Fetch authoritative entity state on uncertain updates; use a provider revision or `updatedAt` only when its semantics are documented. Mark `SYNC_UNKNOWN` on unresolved conflicts.
5. Process asynchronously with bounded retries, poison-message DLQ and alerts. A periodic reconciliation job compares local mirrors with Turaco; the job must respect rate limits.
6. A duplicate CREATE cannot create a second local claim; external mapping and submit idempotency are guarded by unique constraints and reconciliation before retries.

**Testing:** contract tests against recorded fixtures plus a mock Turaco server (WireMock). Development must work with no credentials.

---

## 7. Technology decision table

| Layer | Preferred | Alternatives | Adoption gate |
|---|---|---|---|
| Insurance API | Java + Spring Boot | n/a | Verify Java/Spring version compatibility (see section 12) |
| Database | PostgreSQL + Flyway | Managed equivalent | Testcontainers-based tests |
| Dashboard | React | n/a | Role-based review UX |
| Channel | Meta WhatsApp Cloud API | Twilio, Infobip | Signature verification, durability |
| Async | Outbox + AWS SQS | RabbitMQ, Kafka | Existing infra evaluation |
| Media | S3 + FFmpeg | Azure Blob | Encryption, retention |
| Language ID | Validated classifier or explicit choice | n/a | Accuracy per language |
| ASR | Benchmark MMS / XLS-R / managed ASR / Whisper variants | Local specialized models | WER and critical-field accuracy per language |
| Extraction | OpenAI or Anthropic SDK + Pydantic | Local multimodal LLM | Accuracy, privacy, cost |
| Conversation | LangGraph | Spring StateMachine, OpenAI Agents SDK | Multi-turn need justifies it |
| OCR | AWS Textract where supported | Azure Document Intelligence | Field quality on local documents |
| Fraud | Rules, then GBDT if validated | n/a | Low false-positive rate |
| AI observability | Langfuse | LangSmith, Phoenix | Redaction policy |
| AI evaluation | pytest + gold datasets | RAGAS, DeepEval | CI regression gates |
| Deployment | Docker, AWS, Kubernetes | ECS | Staging and rollback |

---

## 8. Claimant verification

A WhatsApp number is a communication address, not proof of identity (SIM swaps and shared phones). Before disclosing policy data or submitting a claim:

1. Match the sender number to a customer record (`mobileNumber`).
2. Use policy number or other known data for record discovery only, never as a second independent authentication factor. Obtain an identity proof through an approved existing Turaco/partner authentication flow or a properly enrolled, independent verified channel. Where no sufficiently strong mechanism exists, require assisted identity verification by authorized staff before protected actions.
3. OTP to the same WhatsApp/SIM alone is not independent proof of identity. For payouts or payout-destination changes require risk-based step-up, independently verified ownership and authorized human review. Do not disclose national ID, birth date, or policy existence in unverified responses.
4. Rate-limit attempts, lock after repeated failures, log all outcomes.
5. Never reveal whether a given policy exists to an unverified sender.

---

## 9. AI contracts

```python
from datetime import date
from enum import Enum
from typing import Generic, TypeVar
from pydantic import BaseModel, Field

T = TypeVar("T")

class FieldStatus(str, Enum):
    EXTRACTED = "extracted"     # model produced it, not yet confirmed
    CONFIRMED = "confirmed"     # customer confirmed
    UNVERIFIED = "unverified"   # low confidence or conflicting evidence
    MISSING = "missing"

class Extracted(BaseModel, Generic[T]):
    value: T | None = None
    status: FieldStatus = FieldStatus.MISSING
    confidence: float | None = Field(default=None, ge=0, le=1)
    evidence_span: str | None = None      # exact quote from transcript or OCR text
    source: str | None = None             # "transcript" | "message" | "document:<id>"

class ClaimExtraction(BaseModel):
    schema_version: str = "2"
    language: str                          # BCP-47, e.g. "sw-KE"
    incident_type_code: Extracted[str]     # must exist in the incident-type catalog
    hospital_name: Extracted[str]
    admission_date: Extracted[date]
    discharge_date: Extracted[date]
    hospital_nights: Extracted[int]        # derived values must be recomputed in Java
    requires_confirmation: bool = True

class ClaimLLMProvider:
    async def extract(self, transcript: str, catalog: list[str]) -> ClaimExtraction: ...
```

Rules:
- The Java service **recomputes** derived values (e.g. nights from dates) and validates `incident_type_code` against the catalog (local seed or Turaco incident types). The model's value is never trusted for money or eligibility.
- Any critical field with `status != CONFIRMED` blocks submission.
- If provider structured-output limits reject generics, emit concrete per-field models (`ExtractedStr`, `ExtractedDate`, `ExtractedInt`).
- Pin SDK and model versions at implementation start; validate against current provider docs.
- No real patient data to any provider without legal/policy approval and a data processing agreement.

---

## 10. Security, privacy and regulatory

- Synthetic fixtures only by default; no real claims in repos, tests or telemetry.
- Verify WhatsApp signatures and provider callbacks; tenant- and role-scoped access.
- Encrypt in transit and at rest; secrets in a secrets manager; redact PII and medical data from LLM traces.
- Immutable audit events for extraction provenance, policy decisions, approvals, payouts.
- Idempotency keys, unique constraints and reconciliation for every financial obligation.
- Agent tools are read-only by default; no autonomous DB mutation or payment authorization.
- **Tenancy and residency:** every business row carries `tenant_id` and `country_code`; configuration defines per-country data residency, retention periods and permitted AI vendors.

**Per-country review (verify each with local counsel before processing real data):**

| Country | Data protection law | Insurance regulator |
|---|---|---|
| Kenya | Data Protection Act, 2019 (ODPC) | Insurance Regulatory Authority |
| Uganda | Data Protection and Privacy Act, 2019 (PDPO) | Insurance Regulatory Authority of Uganda |
| Nigeria | Nigeria Data Protection Act, 2023 (NDPC) | NAICOM |
| Ghana | Data Protection Act, 2012 (Act 843) | National Insurance Commission |
| Zambia | Data Protection Act, 2021 | Pensions and Insurance Authority |

Check cross-border transfer rules for LLM and ASR vendors, health-data classification, consent wording per language, and retention for audio.

---

## 11. Repository layout

```text
turaco-insurance/
  README.md
  docs/ architecture.md api-contracts.md threat-model.md evaluation-plan.md adr/
  services/
    insurance-api/
      src/main/java/.../
        domain/ (customer, product, policy, claim, payout, audit)
        ports/
        adapters/standalone/
        integration/turaco/        # client, DTOs, webhook consumer
        channel/whatsapp/          # gateway module
        workflow/ risk/ review/
      src/main/resources/db/migration/
    ai-service/
      app/api/ app/providers/ app/speech/ app/extraction/ app/workflows/ app/evaluation/
  apps/claims-dashboard/
  infrastructure/ docker/ kubernetes/ terraform/
  tests/contract/ tests/e2e/
  datasets/eval/                   # synthetic or consented, de-identified only
  compose.yaml
  .github/workflows/
```

---

## 12. Reuse audit of kimtour/insurance-policy-api

**Method and limit:** inspected `pom.xml`, `PolicyService.java`, `Policy.java`, `PolicyController.java`, `SecurityConfig.java`, `JwtService.java`, Flyway V1, `compose.yaml`, `application.properties`, `.github/workflows/ci.yml` and repository tree on `main` during this conversation. Test suites and build were not executed. Files not individually inspected remain subject to code review.

**What exists (repository tree, configuration and source inspection):** Java 25, Spring Boot 4.1.1, Hibernate 7, PostgreSQL 17, Flyway, H2 for tests, Spring Security with JWT (OAuth2 resource server), Actuator, multi-stage Dockerfile, Compose, Kubernetes manifests (namespace, postgres, api config, api, example secrets), GitHub Actions CI, 21 tests (JUnit 5, Mockito, MockMvc). Base package `com.sam.insurance`. One migration `V1__create_policy_table.sql` with CHECK constraints (status, dates, premium > 0). Policy fields: `policyNumber`, `customerName`, `customerEmail`, `premium` (`NUMERIC(12,2)` / `BigDecimal`), `status` (PENDING/ACTIVE/CANCELLED/EXPIRED), `startDate`, `endDate`. Endpoints under `/api/policies` (CRUD) and `POST /api/auth/login`.

| Item | Decision | Reason |
|---|---|---|
| Controller / service / repository layering, DTO separation | **Reuse** | Matches target structure |
| `GlobalExceptionHandler` and field-error response shape | **Reuse, extend** | Add error codes and correlation IDs |
| Flyway + `ddl-auto=validate`, DB CHECK constraints | **Reuse** | Keep the three-layer validation approach |
| `BigDecimal` / `NUMERIC(12,2)` money handling | **Reuse** | Add currency column |
| Runtime property placeholders (`${DB_URL}`, `${JWT_SECRET}`) | **Reuse, harden** | `application.properties` uses environment variables, but `compose.yaml` contains fixed local development credentials and JWT secret. Never reuse them outside synthetic local development. |
| Actuator liveness/readiness probes and K8s probe design | **Reuse** | Sound |
| Dockerfile (multi-stage), `compose.yaml` | **Reuse, extend** | Add AI service, dashboard, mock Turaco |
| CI workflow | **Reuse, extend** | Add dependency and image scanning, Python jobs |
| Test patterns (unit, MockMvc) | **Reuse** | |
| In-memory demo user with documented login `sam` / `password123` | **Replace** | Hardcoded demo credential; use an identity provider (Keycloak/Auth0/Cognito) |
| JWT config | **Replace for production** | `JwtService.java` and `SecurityConfig.java` explicitly use HS256 symmetric signing. Use a trusted OIDC provider and asymmetric JWT validation with issuer/audience/scope enforcement. |
| Roles/scopes | **Add** | None documented; need reviewer, claims-officer, admin, service roles |
| `DELETE /api/policies/{id}` hard delete | **Replace** | `PolicyService.deletePolicy` calls `policyRepository.deleteById`; use cancellation with history |
| `GET /api/policies` returns all rows | **Replace** | Add pagination and tenant scoping |
| Long numeric IDs | **Replace** | Use UUIDs plus external reference table (section 6) |
| `customerName`/`customerEmail` on the policy row | **Replace** | Normalize into `customer`; add mobile number (primary WhatsApp identity) |
| `/api/...` unversioned paths | **Change** | Use `/api/v1/...` |
| H2 with Flyway disabled in tests | **Replace for integration tests** | Use Testcontainers PostgreSQL; keep H2 only for pure unit slices |
| Docker Desktop image-import steps, Postgres as a K8s Deployment | **Drop** | Local-only workarounds; use a registry and managed Postgres |
| Java 25 / Spring Boot 4.1.1 | **Verify** | Confirm library and Testcontainers compatibility before locking versions |
| Missing: Customer, Product, Coverage, Claim, Evidence, Review, Payout, Audit, outbox | **Build new** | |

Keep the original repository unchanged. Copy reusable files into `services/insurance-api/` with history noted in the ADR.

---

## 13. Domain model and APIs

**Core resources:** Tenant, Customer, InsuranceProduct, Coverage/Benefit, Policy, Claim, ClaimEvidence, ClaimStateTransition, Conversation, ExtractionResult, RiskAssessment, HumanReview, PaymentObligation, PayoutAttempt, ProviderEvent, ExternalRef, AuditEvent, OutboxEvent.

**Proposed internal endpoints (not Turaco's):**
- `POST /api/v1/customers`, `POST /api/v1/policies`, `GET /api/v1/policies/{id}`
- `POST /api/v1/claims`, `GET /api/v1/claims/{id}`, `POST /api/v1/claims/{id}/evidence`
- `POST /api/v1/claims/{id}/reviews`, `POST /api/v1/claims/{id}/approvals` (authorized reviewer only)
- `GET /api/v1/claims/{id}/audit`
- `POST /webhooks/whatsapp`, `POST /webhooks/turaco`, `POST /webhooks/payments/{provider}`

AI service: `GET /health`, `POST /v1/extract-claim`, `POST /v1/transcribe`, `GET /v1/capabilities`.

---

## 14. Execution plan and gates

**Track S (starts week 1, runs in parallel): language and ASR spike**
S1. Select 2 to 3 candidate ASR options per prioritized language (Swahili, Luganda first).
S2. Define consent, data-collection and annotation plan; recruit native-speaker annotators; write a synthetic Hospital Cash script set (including code-switched samples).
S3. Benchmark WER/CER and critical-field accuracy; fill the capability registry.
**Gate:** each language-modality pair is enabled, human-assisted, or disabled by measured results, not by assumption.

**Phase A. Foundation**
1. Create `turaco-insurance`; keep the source repo unchanged.
2. Import and refactor the policy service per section 12.
3. Migrations: Tenant, Customer, Product, Coverage, Policy, Claim, StateTransition, Audit, Outbox, ExternalRef.
4. Ports with `StandaloneAdapter`; Docker Compose; deterministic fixtures.
**Gate:** integration test creates a policy, submits a mock claim, records a review, returns audit history.

**Phase B. AI extraction**
5. FastAPI service and schema v2. 6. OpenAI adapter and optional Anthropic adapter behind flags. 7. Synthetic multilingual text dataset; measure precision/recall and critical-field error rate.
**Gate:** contract and regression tests pass; uncertain critical fields return as unverified.

**Phase C. Channel and speech**
8. Signed WhatsApp webhook, outbox/SQS, local provider simulator, template registry. 9. Media ingestion, scanning, language preference, ASR router using Track S results. 10. Claimant verification.
**Gate:** duplicate events create no duplicate claims; only validated capabilities are enabled.

**Phase D. Conversation, documents, Turaco**
11. LangGraph conversation and human escalation. 12. OCR and cross-checks. 13. React review queue. 14. `TuracoAdapter`, webhook consumer, reconciliation job, WireMock contract tests.
**Gate:** missing info is requested, corrections applied, reviewer decisions recorded; Turaco webhook duplicates and reordering handled in tests.

**Phase E. Decision and payment**
15. Versioned rules and deterministic benefit calculation. 16. Payout ledger, fake mobile-money provider, reconciliation.
**Gate:** concurrent or retried requests cannot create duplicate payout obligations; ambiguous provider results never trigger blind retries.

**Phase F. Production readiness**
17. Langfuse redacted traces, dashboards, alerts. 18. JUnit, Testcontainers, pytest, contract, load, security and E2E tests. 19. Staging on AWS/Kubernetes with rollback and back-pressure tests. 20. Country privacy and language quality reviews.
**Gate:** approved pilot checklist, documented rollback, monitored staged deployment.

---

## 15. Acceptance metrics

Measure baseline first, then set thresholds after representative evaluation.
- Claim completion rate by country, language, channel, modality.
- ASR WER/CER and critical-field accuracy by language, accent and code-switching.
- Clarification success, confirmation rate, human escalation rate.
- P50/P95 intake-to-submission and claim-resolution time.
- Risk false-positive rate, reviewer override rate, audit completeness.
- Duplicate claim/payout incidents, reconciliation exceptions, webhook backlog.
- Model latency, cost per claim, provider error rate, regression failures.

**Provisional non-functional targets (to confirm with Turaco; used only for sizing):** pilot of up to roughly 1,000 claims per month; webhook acknowledgment under 2 seconds; text-turn reply under 10 seconds P95; voice-note claim extracted within 60 seconds P95; 99.5% availability for the gateway. Replace with agreed numbers.

---

## 16. First engineering sprint (Sprint 1)

Goal: one synthetic Hospital Cash claim from text intake to manual approval and a simulated payout, with no external credentials.

1. Scaffold the monorepo; copy reusable files per section 12 into `services/insurance-api/`.
2. Replace the demo user and login with a local identity provider (Keycloak in Compose); add roles.
3. Migrations for Tenant, Customer, Product, Coverage, Policy, Claim, ClaimStateTransition, AuditEvent; switch to UUIDs and `/api/v1` paths; paginate lists.
4. Define the four ports and `StandaloneAdapter` with seed data (Hospital Cash product, incident types, benefits).
5. `POST /api/v1/claims`, `GET /api/v1/claims/{id}`, review and approval endpoints with immutable state history and audit.
6. FastAPI `/health` and `/v1/extract-claim` with a mock provider and one real provider; schema v2; synthetic English and Swahili fixtures.
7. **Glue:** a script or service call that sends the extraction result to `POST /api/v1/claims` as an unconfirmed intake, then simulates customer confirmation.
8. **Payout-safe stub:** only an authorized standalone approval can reserve a unique `(tenant_id, claim_id, benefit_code, payout_version)` obligation under a database constraint. A fake provider records attempts keyed by stable idempotency reference. Duplicate and concurrent requests yield the same obligation; ambiguous responses enter `UNKNOWN` and cannot be blindly retried. No live transfers.
9. Compose for Postgres, Keycloak, Java service, Python service, React shell; CI for Java and Python tests, dependency scan, Docker builds; Testcontainers Postgres tests.

**Definition of done:** a fresh clone runs with documented setup; tests pass; the mock claim and payout are auditable; no committed nonlocal secrets or hardcoded production credentials; no autonomous financial authorization. Keycloak local demo users may be seeded only from a clearly labeled local-only fixture and are never enabled in staging or production.

**Required automated Sprint 1 checks:**
- `POST /api/v1/claims` from confirmed extraction yields exactly one local claim; repeated client idempotency key returns the same claim.
- Unconfirmed critical field blocks claim submission; an unknown incident code is rejected against the Hospital Cash catalog.
- Reviewer without `claims:approve` cannot approve.
- Concurrent approval requests cannot reserve more than one payout obligation; local mock payment is safe on duplicate calls.
- Claim audit includes who/what/when and reference to reviewed evidence, with no clinical contents in ordinary logs.
- PostgreSQL Testcontainers tests exercise unique constraints and transactional concurrency, not only H2.
- Integration mode with no credentials is disabled safely and does not imply successful Turaco submission.

---

## 17. Open decisions and external dependencies

- Which countries, products and languages are in scope first, and who approves policy terms?
- Real Turaco test API access, permitted use, current OpenAPI spec, base URLs, webhook auth and retry behavior, and whether claim disbursement exists in their API.
- WhatsApp Business account, template approvals per language.
- Approved LLM and ASR vendors; data residency and cross-border transfer rules.
- Who funds and runs native-speaker annotation and consented audio collection (Track S).
- Mobile money provider sandbox and payout approval responsibilities.
- Existing queue, identity provider and observability stack at Turaco, if any.
- Which claims require mandatory human approval.

## 18. Status

Design and build plan only. No new `turaco-insurance` repository or application code has been created by this document. Existing `insurance-policy-api` source files listed in section 12 were inspected; builds/tests were not executed. Turaco documentation was reviewed earlier, and no authenticated Turaco requests were made.

---

## 19. Implementation guardrails for coding agents

- Deliver code and tests in vertical slices, starting in Standalone mode. Never treat a proposed architecture as an implemented feature.
- Preserve `kimtour/insurance-policy-api` unchanged; document source attribution and changes in ADR-001.
- Do not invent Turaco external API fields beyond verified contracts. Put unconfirmed endpoints behind mock interfaces with TODO contract gates.
- Make tenant and country scope mandatory at API, service and query boundaries. Use UUIDs for internal IDs and a separate table for all external IDs.
- Never permit LLMs, Hermes, LangGraph or other assistants to approve a claim, change a payout destination or initiate a live payout.
- Keep model evaluations stratified by country, language, dialect, code-switching and modality. Unsupported speech pairs require human-assisted handling.
- Add complete local setup, sample synthetic fixtures, OpenAPI specs, sequence diagrams and test commands before calling a milestone finished.
- Explicitly document breaking choices, open questions and results of each gate in `docs/decisions/`.
