package com.sam.insurance;

import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskService {
  private final Store db;
  private final ClaimsService claims;

  public RiskService(Store db, ClaimsService claims) {
    this.db = db;
    this.claims = claims;
  }

  @Transactional
  public Map assess(Scope s, UUID id) {
    var claim = claims.get(s, id);
    var extraction = claim.extraction();
    List<String> flags = new ArrayList<>();
    if (!claim.claimant_verified()) flags.add("IDENTITY_REQUIRES_ASSISTED_VERIFICATION");
    if (!claim.payee_verified()) flags.add("PAYEE_OWNERSHIP_UNVERIFIED");
    for (var field :
        List.of(
            extraction.incident_type_code(),
            extraction.hospital_name(),
            extraction.admission_date(),
            extraction.discharge_date()))
      if (field.value() == null || field.status() != Contracts.FieldStatus.confirmed) {
        flags.add("CRITICAL_FIELDS_REQUIRE_CONFIRMATION");
        break;
      }
    if (extraction.admission_date().value() != null
        && extraction.discharge_date().value() != null
        && ChronoUnit.DAYS.between(
                extraction.admission_date().value(), extraction.discharge_date().value())
            > 30) flags.add("STAY_EXCEEDS_SYNTHETIC_BENEFIT_CAP");
    if (!db.rows(
            s,
            "SELECT id FROM claim_evidence WHERE tenant_id=? AND country_code=? AND claim_id=? AND"
                + " verification_status IN ('CONFLICT','QUARANTINED','UNVERIFIED')",
            id)
        .isEmpty()) flags.add("EVIDENCE_REQUIRES_HUMAN_REVIEW");
    UUID assessment = UUID.randomUUID();
    db.update(
        s,
        "INSERT INTO"
            + " risk_assessment(tenant_id,country_code,id,claim_id,rule_version,flags,route,actor)"
            + " VALUES(?,?,?,?, 'synthetic-risk-v1',?::jsonb,'HUMAN_REVIEW',?)",
        assessment,
        id,
        db.json(flags),
        s.actor());
    db.audit(
        s,
        id,
        "RISK_ASSESSED",
        Map.of("assessment_id", assessment, "flags", flags, "route", "HUMAN_REVIEW"),
        claims.policyVersion(s, id));
    return Map.of(
        "id",
        assessment,
        "flags",
        flags,
        "route",
        "HUMAN_REVIEW",
        "rule_version",
        "synthetic-risk-v1",
        "automatic_decision",
        false);
  }
}
