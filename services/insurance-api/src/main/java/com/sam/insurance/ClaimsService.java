package com.sam.insurance;

import static com.sam.insurance.Contracts.*;
import static org.springframework.http.HttpStatus.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimsService {
  public static final Set<String> CRITICAL =
      Set.of("incident_type_code", "hospital_name", "admission_date", "discharge_date");
  private final Store db;
  private final StandaloneAdapter adapter;

  public ClaimsService(Store db, StandaloneAdapter adapter) {
    this.db = db;
    this.adapter = adapter;
  }

  private void require(boolean yes, String message) {
    if (!yes) throw new ResponseStatusException(CONFLICT, message);
  }

  Map<String, Object> lock(Scope s, UUID id) {
    return db.one(
        s, "SELECT * FROM claim WHERE tenant_id=? AND country_code=? AND id=? FOR UPDATE", id);
  }

  Extraction extraction(Map<String, Object> row) {
    return db.parse(row.get("extraction"), Extraction.class);
  }

  private List<Extracted<?>> fields(Extraction e) {
    return List.of(
        e.incident_type_code(), e.hospital_name(), e.admission_date(), e.discharge_date());
  }

  private void provenance(Extraction e) {
    for (Extracted<?> f : fields(e)) {
      require(
          (!(f.value() instanceof String text) || !text.isBlank())
              && f.value() != null
              && f.status() != FieldStatus.missing
              && f.status() != FieldStatus.unverified,
          "Critical values are missing or unverified");
      require(
          f.evidence_span() != null
              && !f.evidence_span().isBlank()
              && f.source() != null
              && !f.source().isBlank(),
          "Critical fields require evidence and source");
    }
  }

  private <T> Extracted<T> mark(Extracted<T> f, FieldStatus status) {
    return new Extracted<>(
        f.value(),
        f.value() == null
            ? FieldStatus.missing
            : (status == FieldStatus.extracted && f.status() == FieldStatus.unverified
                ? FieldStatus.unverified
                : status),
        f.confidence(),
        f.evidence_span(),
        f.source());
  }

  Extraction mark(Extraction e, FieldStatus status) {
    return new Extraction(
        "2",
        e.language(),
        mark(e.incident_type_code(), status),
        mark(e.hospital_name(), status),
        mark(e.admission_date(), status),
        mark(e.discharge_date(), status),
        mark(e.hospital_nights(), FieldStatus.unverified),
        status != FieldStatus.confirmed);
  }

  void version(Scope s, UUID id, Extraction e) {
    db.update(
        s,
        "INSERT INTO extraction_version(tenant_id,country_code,id,claim_id,extraction,actor)"
            + " VALUES(?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        id,
        db.json(e),
        s.actor());
  }

  int policyVersion(Scope s, UUID id) {
    return ((Number)
            db.one(
                    s,
                    "SELECT p.version FROM policy p JOIN claim c ON p.id=c.policy_id AND"
                        + " p.tenant_id=c.tenant_id AND p.country_code=c.country_code WHERE"
                        + " c.tenant_id=? AND c.country_code=? AND c.id=?",
                    id)
                .get("version"))
        .intValue();
  }

  void move(Scope s, UUID id, String machine, String from, String to) {
    if (from.equals(to)) return;
    String column =
        switch (machine) {
          case "intake" -> "intake_status";
          case "provider" -> "provider_claim_status";
          case "payout" -> "payout_status";
          default -> throw new IllegalArgumentException();
        };
    db.update(
        s,
        "UPDATE claim SET "
            + column
            + "=v.state FROM (SELECT ?::uuid t,?::text c,?::text state) v WHERE tenant_id=v.t AND"
            + " country_code=v.c AND id=?",
        to,
        id);
    int policyVersion = policyVersion(s, id);
    db.update(
        s,
        "INSERT INTO"
            + " claim_state_transition(tenant_id,country_code,id,claim_id,machine,from_state,to_state,actor,source,policy_version,correlation_id)"
            + " VALUES(?,?,?,?,?,?,?,?,'standalone',?,?)",
        UUID.randomUUID(),
        id,
        machine,
        from,
        to,
        s.actor(),
        policyVersion,
        CorrelationFilter.current());
    db.audit(
        s, id, "STATE_CHANGED", Map.of("machine", machine, "from", from, "to", to), policyVersion);
  }

  @Transactional
  public ClaimView create(Scope s, String key, CreateClaim input) {
    if (key == null || !key.matches("[A-Za-z0-9:_-]{1,120}"))
      throw new ResponseStatusException(BAD_REQUEST, "A valid Idempotency-Key is required");
    String digest;
    try {
      digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(db.json(input).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    adapter.findPolicy(s, input.policy_id());
    Extraction e = input.extraction();
    if (e.incident_type_code().value() != null
        && !adapter.listIncidentTypes(s).contains(e.incident_type_code().value()))
      throw new ResponseStatusException(BAD_REQUEST, "Unknown Hospital Cash incident code");
    if (e.admission_date().value() != null && e.discharge_date().value() != null)
      require(
          e.discharge_date().value().isAfter(e.admission_date().value()),
          "Discharge must follow admission");
    UUID id = UUID.randomUUID();
    Extraction unconfirmed = mark(e, FieldStatus.extracted);
    int inserted =
        db.update(
            s,
            "INSERT INTO"
                + " claim(tenant_id,country_code,id,policy_id,client_key,request_digest,extraction,intake_status,provider_claim_status,payout_status)"
                + " VALUES(?,?,?,?,?,?,?::jsonb,'NEEDS_CONFIRMATION','NOT_SUBMITTED','NOT_AUTHORIZED')"
                + " ON CONFLICT(tenant_id,country_code,client_key) DO NOTHING",
            id,
            input.policy_id(),
            key,
            digest,
            db.json(unconfirmed));
    if (inserted == 0) {
      Map<String, Object> existing =
          db.one(
              s,
              "SELECT id,request_digest FROM claim WHERE tenant_id=? AND country_code=? AND"
                  + " client_key=?",
              key);
      require(
          digest.equals(existing.get("request_digest")),
          "Idempotency key was already used for a different request");
      return get(s, (UUID) existing.get("id"));
    }
    version(s, id, unconfirmed);
    db.audit(s, id, "INTAKE_CREATED", Map.of("policy_id", input.policy_id()), policyVersion(s, id));
    return get(s, id);
  }

  public ClaimView get(Scope s, UUID id) {
    Map<String, Object> r =
        db.one(
            s,
            "SELECT c.*,o.id obligation_id,o.amount,o.currency FROM claim c LEFT JOIN"
                + " payment_obligation o ON c.id=o.claim_id AND c.tenant_id=o.tenant_id AND"
                + " c.country_code=o.country_code WHERE c.tenant_id=? AND c.country_code=? AND"
                + " c.id=?",
            id);
    return new ClaimView(
        (UUID) r.get("id"),
        (UUID) r.get("policy_id"),
        r.get("intake_status").toString(),
        r.get("provider_claim_status").toString(),
        r.get("payout_status").toString(),
        (boolean) r.get("claimant_verified"),
        (boolean) r.get("payee_verified"),
        extraction(r),
        (UUID) r.get("obligation_id"),
        (BigDecimal) r.get("amount"),
        (String) r.get("currency"),
        (String) r.get("provider_kind"),
        ((Number) r.get("provider_revision")).longValue());
  }

  public List<ClaimView> list(Scope s, int page, int size) {
    return db
        .rows(
            s,
            "SELECT id FROM claim WHERE tenant_id=? AND country_code=? ORDER BY created_at DESC,id"
                + " LIMIT ? OFFSET ?",
            size,
            page * size)
        .stream()
        .map(r -> get(s, (UUID) r.get("id")))
        .toList();
  }

  @Transactional
  public ClaimView confirm(Scope s, UUID id, Confirmation input) {
    Map<String, Object> row = lock(s, id);
    require(
        Set.of("NEEDS_CONFIRMATION", "VERIFIED").contains(row.get("intake_status")),
        "Intake cannot be confirmed in this state");
    require(
        input.fields().equals(CRITICAL),
        "Explicit confirmation of all four critical fields is required");
    Extraction e = extraction(row);
    provenance(e);
    Extraction confirmed = mark(e, FieldStatus.confirmed);
    db.update(
        s,
        "UPDATE claim SET extraction=v.data FROM (SELECT ?::uuid t,?::text c,?::jsonb data) v WHERE"
            + " tenant_id=v.t AND country_code=v.c AND id=?",
        db.json(confirmed),
        id);
    version(s, id, confirmed);
    move(
        s,
        id,
        "intake",
        row.get("intake_status").toString(),
        (boolean) row.get("claimant_verified") ? "READY_TO_SUBMIT" : "VERIFIED");
    db.audit(
        s,
        id,
        "CUSTOMER_CONFIRMED",
        Map.of("evidence_reference", input.evidence_reference(), "fields", CRITICAL),
        policyVersion(s, id));
    return get(s, id);
  }

  @Transactional
  public ClaimView verify(Scope s, UUID id, Verification input) {
    Map<String, Object> row = lock(s, id);
    require(
        !"APPROVED".equals(row.get("provider_claim_status")), "Approved payee cannot be changed");
    db.update(
        s,
        "UPDATE claim SET claimant_verified=true,payee_verified=v.verified FROM (SELECT ?::uuid"
            + " t,?::text c,?::boolean verified) v WHERE tenant_id=v.t AND country_code=v.c AND"
            + " id=?",
        input.payee_verified(),
        id);
    if ("VERIFIED".equals(row.get("intake_status")))
      move(s, id, "intake", "VERIFIED", "READY_TO_SUBMIT");
    db.audit(
        s,
        id,
        "ASSISTED_IDENTITY_VERIFIED",
        Map.of(
            "evidence_reference",
            input.evidence_reference(),
            "payee_verified",
            input.payee_verified()),
        policyVersion(s, id));
    return get(s, id);
  }

  Map<String, Object> eligibility(Scope s, Map<String, Object> row) {
    Extraction e = extraction(row);
    provenance(e);
    require(
        fields(e).stream().allMatch(f -> f.status() == FieldStatus.confirmed),
        "Critical fields need customer confirmation");
    LocalDate admission = e.admission_date().value(), discharge = e.discharge_date().value();
    long nights = ChronoUnit.DAYS.between(admission, discharge);
    require(
        nights > 0 && nights <= 365 && !discharge.isAfter(LocalDate.now(java.time.ZoneOffset.UTC)),
        "Invalid hospital stay dates");
    Map<String, Object> p =
        db.one(
            s,
            "SELECT * FROM policy WHERE tenant_id=? AND country_code=? AND id=? FOR SHARE",
            row.get("policy_id"));
    require(
        adapter.checkActive(p, admission, discharge),
        "Policy is inactive or stay falls outside coverage");
    Map<String, Object> benefit =
        adapter.listBenefits(s, (UUID) row.get("policy_id"), e.incident_type_code().value());
    require(
        !admission.isBefore(
            LocalDate.parse(p.get("start_date").toString())
                .plusDays(((Number) benefit.get("waiting_days")).intValue())),
        "Policy waiting period has not elapsed");
    benefit.put(
        "calculated_amount",
        ((BigDecimal) benefit.get("daily_amount"))
            .multiply(
                BigDecimal.valueOf(
                    Math.min(nights, ((Number) benefit.get("max_nights")).longValue()))));
    benefit.put("policy_version", p.get("version"));
    return benefit;
  }

  @Transactional
  public ClaimView submit(Scope s, UUID id) {
    Map<String, Object> row = lock(s, id);
    if ("SUBMITTED".equals(row.get("intake_status"))) return get(s, id);
    require(
        "READY_TO_SUBMIT".equals(row.get("intake_status"))
            && (boolean) row.get("claimant_verified"),
        "Confirmed fields and assisted claimant verification are required");
    eligibility(s, row);
    move(s, id, "intake", "READY_TO_SUBMIT", "SUBMITTED");
    if ("NOT_SUBMITTED".equals(row.get("provider_claim_status"))) {
      move(s, id, "provider", "NOT_SUBMITTED", "SUBMISSION_PENDING");
      move(s, id, "provider", "SUBMISSION_PENDING", adapter.submitClaim(s, id));
    }
    return get(s, id);
  }

  @Transactional
  public ClaimView review(Scope s, UUID id, Review input) {
    Map<String, Object> row = lock(s, id);
    require(
        ("RECEIVED".equals(row.get("provider_claim_status"))
            || ("LOCAL_SIMULATOR".equals(row.get("provider_kind"))
                && Set.of("PROCESSING", "APPROVED").contains(row.get("provider_claim_status")))),
        "Only a submitted claim awaiting local review can be reviewed");
    db.update(
        s,
        "INSERT INTO"
            + " review(tenant_id,country_code,id,claim_id,actor,decision,reason_code,evidence_reference)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        s.actor(),
        input.decision(),
        input.reason_code(),
        input.evidence_reference());
    db.audit(
        s,
        id,
        "REVIEW_RECORDED",
        Map.of(
            "decision",
            input.decision(),
            "reason_code",
            input.reason_code(),
            "evidence_reference",
            input.evidence_reference()),
        policyVersion(s, id));
    if ("REQUEST_INFORMATION".equals(input.decision()))
      move(s, id, "intake", row.get("intake_status").toString(), "NEEDS_CONFIRMATION");
    return get(s, id);
  }

  @Transactional
  public ClaimView approve(Scope s, UUID id, Approval input) {
    Map<String, Object> row = lock(s, id);
    if (get(s, id).obligation_id() != null) return get(s, id);
    require(
        ("LOCAL_SIMULATOR".equals(row.get("provider_kind"))
                ? "APPROVED".equals(row.get("provider_claim_status"))
                : "RECEIVED".equals(row.get("provider_claim_status")))
            && (boolean) row.get("payee_verified"),
        "Submitted claim and independently verified payee are required");
    List<Map<String, Object>> reviews =
        db.rows(
            s,
            "SELECT decision FROM review WHERE tenant_id=? AND country_code=? AND claim_id=? ORDER"
                + " BY sequence DESC LIMIT 1",
            id);
    require(
        !reviews.isEmpty() && "RECOMMEND_APPROVAL".equals(reviews.getFirst().get("decision")),
        "Latest review must recommend approval");
    require(
        db.rows(
                s,
                "SELECT e.id FROM claim_evidence e LEFT JOIN evidence_resolution r ON"
                    + " e.id=r.evidence_id AND e.tenant_id=r.tenant_id AND"
                    + " e.country_code=r.country_code WHERE e.tenant_id=? AND e.country_code=? AND"
                    + " e.claim_id=? AND e.verification_status<>'MATCH' AND r.id IS NULL",
                id)
            .isEmpty(),
        "Evidence discrepancy requires corrected evidence and human review");
    Map<String, Object> b = eligibility(s, row);
    UUID obligation = UUID.randomUUID();
    db.update(
        s,
        "INSERT INTO"
            + " payment_obligation(tenant_id,country_code,id,claim_id,benefit_code,payout_version,amount,currency,status,idempotency_reference,rule_version,policy_version)"
            + " VALUES(?,?,?,?,?,1,?,?,'RESERVED',?,?,?)",
        obligation,
        id,
        b.get("benefit_code"),
        b.get("calculated_amount"),
        b.get("currency"),
        "mock:" + obligation,
        b.get("rule_version"),
        b.get("policy_version"));
    if (!"LOCAL_SIMULATOR".equals(row.get("provider_kind")))
      move(s, id, "provider", "RECEIVED", "APPROVED");
    move(s, id, "payout", "NOT_AUTHORIZED", "RESERVED");
    db.audit(
        s,
        id,
        "APPROVED_BY_HUMAN",
        Map.of(
            "evidence_reference",
            input.evidence_reference(),
            "obligation_id",
            obligation,
            "rule_version",
            b.get("rule_version")),
        ((Number) b.get("policy_version")).intValue());
    return get(s, id);
  }

  @Transactional
  public ClaimView simulate(Scope s, UUID id, Payment input) {
    Map<String, Object> row = lock(s, id);
    if ("CONFIRMED".equals(row.get("payout_status"))) return get(s, id);
    require(
        "APPROVED".equals(row.get("provider_claim_status"))
            && "RESERVED".equals(row.get("payout_status")),
        "Payout must be reserved; UNKNOWN requires reconciliation and cannot be retried");
    Map<String, Object> o =
        db.one(
            s,
            "SELECT * FROM payment_obligation WHERE tenant_id=? AND country_code=? AND claim_id=?"
                + " FOR UPDATE",
            id);
    db.update(
        s,
        "INSERT INTO"
            + " payout_attempt(tenant_id,country_code,id,obligation_id,idempotency_reference,provider,outcome)"
            + " VALUES(?,?,?,?,?,'LOCAL_FAKE',?)",
        UUID.randomUUID(),
        o.get("id"),
        o.get("idempotency_reference"),
        input.outcome());
    db.update(
        s,
        "UPDATE payment_obligation SET status=v.state FROM (SELECT ?::uuid t,?::text c,?::text"
            + " state) v WHERE tenant_id=v.t AND country_code=v.c AND id=?",
        input.outcome(),
        o.get("id"));
    move(s, id, "payout", "RESERVED", "SUBMITTED");
    move(s, id, "payout", "SUBMITTED", input.outcome());
    db.audit(
        s,
        id,
        "SIMULATED_PAYOUT_" + input.outcome(),
        Map.of("obligation_id", o.get("id"), "provider", "LOCAL_FAKE"),
        ((Number) o.get("policy_version")).intValue());
    return get(s, id);
  }

  public List<Map<String, Object>> audit(Scope s, UUID id) {
    get(s, id);
    return db.rows(
        s,
        "SELECT"
            + " id,actor,event_type,detail::text,correlation_id,source,policy_version,created_at::text"
            + " FROM audit_event WHERE tenant_id=? AND country_code=? AND aggregate_id=? ORDER BY"
            + " sequence",
        id);
  }
}
