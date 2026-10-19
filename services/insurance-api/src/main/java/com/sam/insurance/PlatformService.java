package com.sam.insurance;

import static com.sam.insurance.PlatformContracts.*;
import static org.springframework.http.HttpStatus.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlatformService {
  private final Store db;
  private final ClaimsService claims;
  private final ExternalServices external;
  private final EncryptedPayloads encryption;
  private final CompletenessService quality;

  public PlatformService(
      Store db,
      ClaimsService claims,
      ExternalServices external,
      EncryptedPayloads encryption,
      CompletenessService quality) {
    this.db = db;
    this.claims = claims;
    this.external = external;
    this.encryption = encryption;
    this.quality = quality;
  }

  public Contracts.ClaimView existing(Scope s, UUID id) {
    return claims.get(s, id);
  }

  private void require(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(CONFLICT, message);
  }

  @Transactional
  public Contracts.ClaimView fromText(Scope s, String key, TextIntake input) {
    claims.lockCreation(s, key);
    String digest = EncryptedPayloads.digest(db.json(input));
    var existing =
        db.rows(
            s,
            "SELECT id,text_request_digest FROM claim WHERE tenant_id=? AND country_code=? AND"
                + " client_key=?",
            key);
    if (!existing.isEmpty() && existing.getFirst().get("text_request_digest") != null) {
      require(
          digest.equals(existing.getFirst().get("text_request_digest")),
          "Idempotency key was already used for a different text request");
      return claims.get(s, (UUID) existing.getFirst().get("id"));
    }
    try {
      var extracted = external.extract(input.text(), input.language());
      var claim = claims.create(s, key, new Contracts.CreateClaim(input.policy_id(), extracted));
      if (extracted.model_trace() != null) {
        Map<String, String> safe = new LinkedHashMap<>();
        for (String name :
            List.of(
                "trace_id",
                "correlation_id",
                "provider",
                "model",
                "prompt_version",
                "evaluation_status")) {
          String value = extracted.model_trace().get(name);
          if (value != null && value.length() <= 250) safe.put(name, value);
        }
        db.update(
            s,
            "INSERT INTO ai_task_trace(tenant_id,country_code,id,claim_id,metadata)"
                + " VALUES(?,?,?,?,?::jsonb)",
            UUID.randomUUID(),
            claim.id(),
            db.json(safe));
      }
      db.update(
          s,
          "UPDATE claim SET text_request_digest=v.digest FROM (SELECT ?::uuid t,?::text c,?::text"
              + " digest)v WHERE tenant_id=v.t AND country_code=v.c AND id=?",
          digest,
          claim.id());
      return claim;
    } catch (org.springframework.web.client.RestClientException ex) {
      throw new ResponseStatusException(
          BAD_GATEWAY, "Extraction unavailable; request human assistance");
    }
  }

  @Transactional
  public Contracts.ClaimView correct(Scope s, UUID id, Correction input) {
    Map row = claims.lock(s, id);
    require(
        "NEEDS_CONFIRMATION".equals(row.get("intake_status")),
        "Only intake awaiting information may be corrected");
    require(
        "STANDALONE".equals(row.get("provider_kind")),
        "Provider-submitted corrections require a verified external contract");
    if (input.extraction().incident_type_code().value() != null)
      require(
          "HOSPITALIZATION".equals(input.extraction().incident_type_code().value()),
          "Unknown incident code");
    var next = claims.mark(input.extraction(), Contracts.FieldStatus.extracted);
    db.update(
        s,
        "UPDATE claim SET extraction=v.data FROM(SELECT ?::uuid t,?::text c,?::jsonb data)v WHERE"
            + " tenant_id=v.t AND country_code=v.c AND id=?",
        db.json(next),
        id);
    claims.version(s, id, next);
    db.audit(
        s,
        id,
        "INTAKE_CORRECTED",
        Map.of("evidence_reference", input.evidence_reference()),
        claims.policyVersion(s, id));
    return claims.get(s, id);
  }

  @Transactional
  public Map<String, Object> evidence(Scope s, UUID id, Evidence input) {
    Map row = claims.lock(s, id);
    if (input.document_type() != null) {
      Map policy =
          db.one(
              s,
              "SELECT customer_id FROM policy WHERE tenant_id=? AND country_code=? AND id=?",
              row.get("policy_id"));
      require(
          policy.get("customer_id").equals(input.customer_id()),
          "Typed evidence must identify the policy customer");
    } else
      require(
          input.customer_id() == null,
          "A document type is required for customer-associated evidence");
    require(
        !Set.of("APPROVED", "REJECTED", "CLOSED").contains(row.get("provider_claim_status")),
        "Decided claims do not accept new evidence");
    String status;
    Map result;
    if (input.synthetic_text().contains("EICAR-STANDARD-ANTIVIRUS-TEST-FILE")) {
      status = "QUARANTINED";
      result =
          Map.of("status", status, "engine", "local-test-signature", "requires_human_review", true);
    } else {
      try {
        result = external.document(input.synthetic_text(), claims.extraction(row));
        status = result.get("status").toString();
      } catch (org.springframework.web.client.RestClientException ex) {
        throw new ResponseStatusException(BAD_GATEWAY, "Document check unavailable");
      }
    }
    UUID evidence = UUID.randomUUID();
    byte[] ciphertext =
        encryption.encrypt(input.synthetic_text(), EncryptedPayloads.context(s, evidence));
    db.update(
        s,
        "INSERT INTO"
            + " claim_evidence(tenant_id,country_code,id,claim_id,media_type,ciphertext,digest,verification_status,retention_until,actor,extraction_digest,document_type,customer_id,extraction_confidence)"
            + " VALUES(?,?,?,?,'text/plain',?,?,?,now()+interval '30 days',?,?,?,?,?)",
        evidence,
        id,
        ciphertext,
        EncryptedPayloads.digest(input.synthetic_text()),
        status,
        s.actor(),
        claims.evidenceDigest(claims.extraction(row)),
        input.document_type(),
        input.customer_id(),
        input.extraction_confidence());
    claims.refreshReadiness(s, id);
    db.audit(
        s,
        id,
        "EVIDENCE_RECORDED",
        Map.of(
            "evidence_id", evidence, "verification_status", status, "engine", result.get("engine")),
        claims.policyVersion(s, id));
    return Map.of(
        "id", evidence, "verification_status", status, "check", result, "encrypted", true);
  }

  public List<Map<String, Object>> evidenceList(Scope s, UUID id) {
    String current = claims.evidenceDigest(claims.get(s, id).extraction());
    Set<UUID> resolved = new HashSet<>();
    for (var row :
        db.rows(
            s,
            "SELECT r.evidence_id FROM evidence_resolution r JOIN claim_evidence replacement ON"
                + " replacement.id=r.replacement_id AND replacement.tenant_id=r.tenant_id AND"
                + " replacement.country_code=r.country_code WHERE r.tenant_id=? AND"
                + " r.country_code=? AND replacement.claim_id=? AND"
                + " replacement.verification_status='MATCH' AND replacement.extraction_digest=?",
            id,
            current)) resolved.add((UUID) row.get("evidence_id"));
    return db
        .rows(
            s,
            "SELECT"
                + " id,media_type,digest,verification_status,retention_until::text,created_at::text,extraction_digest,document_type,customer_id,extraction_confidence"
                + " FROM claim_evidence WHERE tenant_id=? AND country_code=? AND claim_id=? ORDER"
                + " BY created_at",
            id)
        .stream()
        .map(
            row -> {
              Map<String, Object> metadata = new LinkedHashMap<>(row);
              metadata.put("facts_current", current.equals(metadata.remove("extraction_digest")));
              metadata.put("resolved", resolved.contains(row.get("id")));
              return metadata;
            })
        .toList();
  }

  @Transactional
  public Map resolveEvidence(Scope s, UUID claim, UUID evidence, Resolution input) {
    Map row = claims.lock(s, claim);
    Map old =
        db.one(
            s,
            "SELECT claim_id,verification_status,extraction_digest FROM claim_evidence WHERE"
                + " tenant_id=? AND country_code=? AND id=?",
            evidence);
    Map replacement =
        db.one(
            s,
            "SELECT claim_id,verification_status,extraction_digest FROM claim_evidence WHERE"
                + " tenant_id=? AND country_code=? AND id=?",
            input.replacement_id());
    require(
        claim.equals(old.get("claim_id"))
            && claim.equals(replacement.get("claim_id"))
            && "MATCH".equals(replacement.get("verification_status"))
            && !evidence.equals(input.replacement_id())
            && claims
                .evidenceDigest(claims.extraction(row))
                .equals(replacement.get("extraction_digest"))
            && !"QUARANTINED".equals(old.get("verification_status")),
        "Resolution requires matching replacement evidence on the same claim; quarantine cannot be"
            + " overridden");
    db.update(
        s,
        "INSERT INTO"
            + " evidence_resolution(tenant_id,country_code,id,evidence_id,replacement_id,actor,evidence_reference)"
            + " VALUES(?,?,?,?,?,?,?) ON"
            + " CONFLICT(tenant_id,country_code,evidence_id,replacement_id) DO NOTHING",
        UUID.randomUUID(),
        evidence,
        input.replacement_id(),
        s.actor(),
        input.evidence_reference());
    db.audit(
        s,
        claim,
        "EVIDENCE_DISCREPANCY_RESOLVED",
        Map.of(
            "evidence_id",
            evidence,
            "replacement_id",
            input.replacement_id(),
            "evidence_reference",
            input.evidence_reference()),
        claims.policyVersion(s, claim));
    return Map.of("status", "RESOLVED");
  }

  @Transactional
  public Contracts.ClaimView reject(Scope s, UUID id, Contracts.Approval input) {
    Map row = claims.lock(s, id);
    require(
        "STANDALONE".equals(row.get("provider_kind")),
        "Local reviewer cannot override provider status");
    if ("REJECTED".equals(row.get("provider_claim_status"))) return claims.get(s, id);
    require(
        "RECEIVED".equals(row.get("provider_claim_status")),
        "Only a submitted undecided claim can be rejected");
    var review =
        db.rows(
            s,
            "SELECT decision FROM review WHERE tenant_id=? AND country_code=? AND claim_id=? ORDER"
                + " BY sequence DESC LIMIT 1",
            id);
    require(
        !review.isEmpty() && "RECOMMEND_REJECTION".equals(review.getFirst().get("decision")),
        "Latest review must recommend rejection");
    claims.move(s, id, "provider", "RECEIVED", "REJECTED");
    db.audit(
        s,
        id,
        "REJECTED_BY_HUMAN",
        Map.of("evidence_reference", input.evidence_reference()),
        claims.policyVersion(s, id));
    return claims.get(s, id);
  }

  @Transactional
  public Contracts.ClaimView providerSubmit(Scope s, UUID id) {
    Map row = claims.lock(s, id);
    quality.requireComplete(s, id);
    if ("LOCAL_SIMULATOR".equals(row.get("provider_kind"))
        && "SUBMITTED".equals(row.get("intake_status"))) return claims.get(s, id);
    require(
        "READY_TO_SUBMIT".equals(row.get("intake_status"))
            && (boolean) row.get("claimant_verified"),
        "Confirmed verified intake is required");
    claims.eligibility(s, row);
    db.update(
        s,
        "UPDATE claim SET provider_kind='LOCAL_SIMULATOR' WHERE tenant_id=? AND country_code=? AND"
            + " id=?",
        id);
    claims.move(s, id, "intake", "READY_TO_SUBMIT", "SUBMITTED");
    claims.move(
        s, id, "provider", row.get("provider_claim_status").toString(), "SUBMISSION_PENDING");
    try {
      applySnapshot(s, id, external.submit(s, id, (UUID) row.get("policy_id")));
    } catch (org.springframework.web.client.RestClientException ex) {
      claims.move(s, id, "provider", "SUBMISSION_PENDING", "SYNC_UNKNOWN");
      db.audit(
          s,
          id,
          "PROVIDER_SUBMISSION_AMBIGUOUS",
          Map.of("provider", "LOCAL_SIMULATOR"),
          claims.policyVersion(s, id));
    }
    return claims.get(s, id);
  }

  private void applySnapshot(Scope s, UUID id, Map snapshot) {
    Map row = claims.lock(s, id);
    require(
        id.toString().equals(snapshot.get("internal_claim_id"))
            && s.tenant().toString().equals(snapshot.get("tenant_id"))
            && s.country().equals(snapshot.get("country_code"))
            && "LOCAL_SIMULATOR".equals(snapshot.get("provider")),
        "Provider snapshot scope mismatch");
    long revision = ((Number) snapshot.get("revision")).longValue();
    long current = ((Number) row.get("provider_revision")).longValue();
    if (revision < current
        || (revision == current && !"SYNC_UNKNOWN".equals(row.get("provider_claim_status"))))
      return;
    String status = snapshot.get("status").toString();
    require(
        Set.of("RECEIVED", "PROCESSING", "APPROVED", "REJECTED", "CLOSED").contains(status),
        "Unmapped provider status");
    db.update(
        s,
        "INSERT INTO"
            + " external_ref(tenant_id,country_code,id,provider,resource_type,internal_id,external_id)"
            + " VALUES(?,?,?,'LOCAL_SIMULATOR','claim',?,?) ON"
            + " CONFLICT(tenant_id,country_code,provider,resource_type,internal_id) DO NOTHING",
        UUID.randomUUID(),
        id,
        snapshot.get("id"));
    db.update(
        s,
        "UPDATE claim SET provider_revision=v.revision FROM(SELECT ?::uuid t,?::text c,?::bigint"
            + " revision)v WHERE tenant_id=v.t AND country_code=v.c AND id=?",
        revision,
        id);
    claims.move(s, id, "provider", row.get("provider_claim_status").toString(), status);
    db.audit(
        s,
        id,
        "PROVIDER_SNAPSHOT_RECONCILED",
        Map.of("provider", "LOCAL_SIMULATOR", "revision", revision),
        claims.policyVersion(s, id));
  }

  @Transactional
  public Map providerEvent(ProviderEvent input) {
    Scope s = new Scope(input.tenant_id(), input.country_code(), "authenticated-local-provider");
    Map row = claims.lock(s, input.internal_claim_id());
    require(
        "LOCAL_SIMULATOR".equals(row.get("provider_kind")), "Claim is not owned by the simulator");
    int fresh =
        db.update(
            s,
            "INSERT INTO provider_applied_event(tenant_id,country_code,event_id) VALUES(?,?,?) ON"
                + " CONFLICT DO NOTHING",
            input.event_id());
    String status = fresh == 0 ? "DUPLICATE" : "RECONCILED";
    if (fresh != 0) {
      try {
        applySnapshot(
            s, input.internal_claim_id(), external.snapshot(s, input.internal_claim_id()));
      } catch (org.springframework.web.client.RestClientException ex) {
        status = "SYNC_UNKNOWN";
        claims.move(
            s,
            input.internal_claim_id(),
            "provider",
            row.get("provider_claim_status").toString(),
            "SYNC_UNKNOWN");
      }
    }
    db.update(
        s,
        "INSERT INTO provider_receipt(tenant_id,country_code,id,claim_id,event_id,revision,status)"
            + " VALUES(?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        input.internal_claim_id(),
        input.event_id(),
        input.revision(),
        status);
    return Map.of("status", status, "receipt_retained", true);
  }

  @Transactional
  public Contracts.ClaimView reconcileProvider(Scope s, UUID id) {
    Map row = claims.lock(s, id);
    require("LOCAL_SIMULATOR".equals(row.get("provider_kind")), "Not a provider-simulated claim");
    try {
      applySnapshot(s, id, external.snapshot(s, id));
    } catch (org.springframework.web.client.RestClientException ex) {
      throw new ResponseStatusException(
          BAD_GATEWAY, "Provider unavailable; no automatic resubmission was made");
    }
    return claims.get(s, id);
  }

  @Transactional
  public Map paymentReceipt(Scope s, UUID id, Receipt input) {
    Map row = claims.lock(s, id);
    require(
        "UNKNOWN".equals(row.get("payout_status")),
        "Receipt simulation is only for an ambiguous local payout");
    var claim = claims.get(s, id);
    db.update(
        s,
        "INSERT INTO"
            + " mock_payment_receipt(tenant_id,country_code,id,obligation_id,outcome,evidence_reference,actor)"
            + " VALUES(?,?,?,?,?,?,?) ON CONFLICT(tenant_id,country_code,obligation_id) DO NOTHING",
        UUID.randomUUID(),
        claim.obligation_id(),
        input.outcome(),
        input.evidence_reference(),
        s.actor());
    Map receipt =
        db.one(
            s,
            "SELECT outcome FROM mock_payment_receipt WHERE tenant_id=? AND country_code=? AND"
                + " obligation_id=?",
            claim.obligation_id());
    require(
        input.outcome().equals(receipt.get("outcome")), "Conflicting authoritative fake receipt");
    db.audit(
        s,
        id,
        "MOCK_PROVIDER_RECEIPT_RECORDED",
        Map.of("outcome", input.outcome(), "evidence_reference", input.evidence_reference()),
        claims.policyVersion(s, id));
    return receipt;
  }

  @Transactional
  public Contracts.ClaimView reconcilePayment(Scope s, UUID id) {
    Map row = claims.lock(s, id);
    if (Set.of("CONFIRMED", "FAILED").contains(row.get("payout_status"))) return claims.get(s, id);
    require(
        "UNKNOWN".equals(row.get("payout_status")),
        "Only ambiguous payouts require reconciliation");
    var claim = claims.get(s, id);
    Map receipt =
        db.one(
            s,
            "SELECT outcome FROM mock_payment_receipt WHERE tenant_id=? AND country_code=? AND"
                + " obligation_id=?",
            claim.obligation_id());
    String state = receipt.get("outcome").toString();
    db.update(
        s,
        "UPDATE payment_obligation SET status=v.state FROM(SELECT ?::uuid t,?::text c,?::text"
            + " state)v WHERE tenant_id=v.t AND country_code=v.c AND id=?",
        state,
        claim.obligation_id());
    claims.move(s, id, "payout", "UNKNOWN", state);
    db.audit(
        s,
        id,
        "PAYOUT_RECONCILED",
        Map.of("provider", "LOCAL_FAKE", "outcome", state),
        claims.policyVersion(s, id));
    return claims.get(s, id);
  }
}
