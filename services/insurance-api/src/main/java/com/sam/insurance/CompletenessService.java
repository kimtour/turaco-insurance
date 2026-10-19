package com.sam.insurance;

import static org.springframework.http.HttpStatus.*;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CompletenessService {
  private final Store db;

  public CompletenessService(Store db) {
    this.db = db;
  }

  public record Review(
      @NotNull UUID requirement_id,
      @NotNull UUID evidence_id,
      @NotBlank @Pattern(regexp = "VERIFIED|CONFLICT") String decision,
      @NotBlank @Size(max = 200) String evidence_reference) {}

  public record Waiver(
      @NotNull UUID requirement_id, @NotBlank @Size(max = 200) String evidence_reference) {}

  Map<String, Object> claim(Scope s, UUID id) {
    return db.one(
        s,
        "SELECT c.*,p.product_id,p.customer_id FROM claim c JOIN policy p ON p.id=c.policy_id AND"
            + " p.tenant_id=c.tenant_id AND p.country_code=c.country_code WHERE c.tenant_id=? AND"
            + " c.country_code=? AND c.id=?",
        id);
  }

  String digest(Contracts.Extraction e) {
    return EncryptedPayloads.digest(
        db.json(
            Arrays.asList(
                e.language(),
                e.incident_type_code().value(),
                e.hospital_name().value(),
                e.admission_date().value(),
                e.discharge_date().value())));
  }

  List<Map<String, Object>> requirements(Scope s, Map<String, Object> c) {
    var e = db.parse(c.get("extraction"), Contracts.Extraction.class);
    LocalDate day =
        e.admission_date().value() == null
            ? LocalDate.now(java.time.ZoneOffset.UTC)
            : e.admission_date().value();
    String incident =
        e.incident_type_code().value() == null ? "HOSPITALIZATION" : e.incident_type_code().value();
    return db.rows(
        s,
        "SELECT id,document_type,label,version,effective_from::text FROM document_requirement WHERE"
            + " tenant_id=? AND country_code=? AND product_id=? AND incident_type_code=? AND"
            + " effective_from<=? AND (effective_until IS NULL OR effective_until>?) AND"
            + " version=(SELECT max(r.version) FROM document_requirement r WHERE"
            + " r.tenant_id=document_requirement.tenant_id AND"
            + " r.country_code=document_requirement.country_code AND"
            + " r.product_id=document_requirement.product_id AND"
            + " r.incident_type_code=document_requirement.incident_type_code AND"
            + " r.effective_from<=? AND (r.effective_until IS NULL OR r.effective_until>?)) ORDER"
            + " BY document_type",
        c.get("product_id"),
        incident,
        day,
        day,
        day,
        day);
  }

  public Map<String, Object> summary(Scope s, UUID id) {
    Map<String, Object> c = claim(s, id);
    String current = digest(db.parse(c.get("extraction"), Contracts.Extraction.class));
    List<Map<String, Object>> result = new ArrayList<>();
    boolean complete = true;
    List<String> missing = new ArrayList<>();
    for (var requirement : requirements(s, c)) {
      UUID requirementId = (UUID) requirement.get("id");
      String type = requirement.get("document_type").toString();
      var evidence =
          db.rows(
              s,
              "SELECT id,verification_status,extraction_digest,created_at::text FROM claim_evidence"
                  + " WHERE tenant_id=? AND country_code=? AND claim_id=? AND customer_id=? AND"
                  + " document_type=? ORDER BY created_at",
              id,
              c.get("customer_id"),
              type);
      var reviews =
          db.rows(
              s,
              "SELECT decision,evidence_id,actor,evidence_reference,created_at::text FROM"
                  + " document_review WHERE tenant_id=? AND country_code=? AND claim_id=? AND"
                  + " requirement_id=? AND extraction_digest=? ORDER BY sequence DESC LIMIT 1",
              id,
              requirementId,
              current);
      String status = evidence.isEmpty() ? "MISSING" : "PRESENT_UNVERIFIED";
      if (evidence.stream()
          .anyMatch(
              e ->
                  current.equals(e.get("extraction_digest"))
                      && Set.of("CONFLICT", "QUARANTINED").contains(e.get("verification_status"))))
        status = "CONFLICT";
      if (!reviews.isEmpty()) status = reviews.getFirst().get("decision").toString();
      // A later conflicting upload invalidates an earlier verification until reviewed.
      if ("VERIFIED".equals(status) && !evidence.isEmpty()) {
        String reviewedAt = reviews.getFirst().get("created_at").toString();
        if (evidence.stream()
            .anyMatch(
                e ->
                    current.equals(e.get("extraction_digest"))
                        && Set.of("CONFLICT", "QUARANTINED").contains(e.get("verification_status"))
                        && e.get("created_at").toString().compareTo(reviewedAt) > 0))
          status = "CONFLICT";
      }
      boolean satisfied = Set.of("VERIFIED", "WAIVED_BY_AUTHORIZED_REVIEWER").contains(status);
      complete &= satisfied;
      if (!satisfied) missing.add(requirement.get("label").toString());
      Map<String, Object> item = new LinkedHashMap<>(requirement);
      item.put("status", status);
      item.put(
          "evidence",
          evidence.stream()
              .map(
                  e ->
                      Map.of(
                          "id",
                          e.get("id"),
                          "verification_status",
                          e.get("verification_status"),
                          "facts_current",
                          current.equals(e.get("extraction_digest"))))
              .toList());
      if (!reviews.isEmpty()) item.put("review", reviews.getFirst());
      result.add(item);
    }
    return Map.of(
        "claim_id",
        id,
        "customer_id",
        c.get("customer_id"),
        "complete",
        complete,
        "requirements",
        result,
        "missing_documents",
        missing,
        "follow_up",
        missing.isEmpty()
            ? "Required evidence has been reviewed."
            : "Please provide verified evidence for: " + String.join(", ", missing) + ".",
        "catalog",
        "synthetic-hospital-cash-v1",
        "historical_without_requirements",
        result.isEmpty());
  }

  public boolean complete(Scope s, UUID id) {
    return Boolean.TRUE.equals(summary(s, id).get("complete"));
  }

  public void requireComplete(Scope s, UUID id) {
    if (!complete(s, id))
      throw new ResponseStatusException(
          CONFLICT, "Required documents are missing, unverified or conflicting");
  }

  @Transactional
  public Map<String, Object> review(Scope s, UUID id, Review input) {
    return record(
        s,
        id,
        input.requirement_id(),
        input.evidence_id(),
        input.decision(),
        input.evidence_reference());
  }

  @Transactional
  public Map<String, Object> waive(Scope s, UUID id, Waiver input) {
    return record(
        s,
        id,
        input.requirement_id(),
        null,
        "WAIVED_BY_AUTHORIZED_REVIEWER",
        input.evidence_reference());
  }

  private Map<String, Object> record(
      Scope s, UUID id, UUID requirement, UUID evidence, String decision, String reference) {
    db.one(s, "SELECT id FROM claim WHERE tenant_id=? AND country_code=? AND id=? FOR UPDATE", id);
    var c = claim(s, id);
    BillingService.require(
        "NOT_AUTHORIZED".equals(c.get("payout_status"))
            && !Set.of("REJECTED", "CLOSED").contains(c.get("provider_claim_status")),
        "Decided claims do not accept document decisions");
    var active =
        requirements(s, c).stream()
            .filter(r -> requirement.equals(r.get("id")))
            .findFirst()
            .orElseThrow(
                () ->
                    new ResponseStatusException(NOT_FOUND, "Active scoped requirement not found"));
    String current = digest(db.parse(c.get("extraction"), Contracts.Extraction.class));
    if (evidence != null) {
      var e =
          db.one(
              s,
              "SELECT * FROM claim_evidence WHERE tenant_id=? AND country_code=? AND id=?",
              evidence);
      BillingService.require(
          id.equals(e.get("claim_id"))
              && c.get("customer_id").equals(e.get("customer_id"))
              && active.get("document_type").equals(e.get("document_type"))
              && current.equals(e.get("extraction_digest")),
          "Evidence must belong to the current claim, customer, requirement and facts");
      BillingService.require(
          !"QUARANTINED".equals(e.get("verification_status")),
          "Quarantined evidence cannot be verified");
      if ("VERIFIED".equals(decision))
        BillingService.require(
            "MATCH".equals(e.get("verification_status")),
            "Only current matching evidence may be verified");
    }
    db.update(
        s,
        "INSERT INTO"
            + " document_review(tenant_id,country_code,id,claim_id,requirement_id,evidence_id,extraction_digest,decision,actor,evidence_reference)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        requirement,
        evidence,
        current,
        decision,
        s.actor(),
        reference);
    db.audit(
        s,
        id,
        "DOCUMENT_" + decision,
        Map.of("requirement_id", requirement, "evidence_reference", reference),
        null);
    return summary(s, id);
  }
}
