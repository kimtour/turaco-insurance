package com.sam.insurance;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StandaloneAdapter
    implements Ports.PolicyPort,
        Ports.CustomerPort,
        Ports.ProductCatalogPort,
        Ports.ClaimSubmissionPort {
  private final Store db;

  public StandaloneAdapter(Store db) {
    this.db = db;
  }

  public Map<String, Object> findPolicy(Scope s, UUID id) {
    return db.one(s, "SELECT * FROM policy WHERE tenant_id=? AND country_code=? AND id=?", id);
  }

  public Map<String, Object> findCustomer(Scope s, UUID id) {
    return db.one(
        s,
        "SELECT id,full_name,mobile_number FROM customer WHERE tenant_id=? AND country_code=? AND"
            + " id=?",
        id);
  }

  @Transactional
  public UUID createCustomer(Scope s, Contracts.CustomerInput input) {
    UUID id = UUID.randomUUID();
    db.update(
        s,
        "INSERT INTO customer(tenant_id,country_code,id,full_name,mobile_number) VALUES(?,?,?,?,?)",
        id,
        input.full_name(),
        input.mobile_number());
    db.audit(s, id, "CUSTOMER_CREATED", Map.of(), null);
    return id;
  }

  public List<Map<String, Object>> listProducts(Scope s) {
    return db.rows(s, "SELECT id,code,name FROM product WHERE tenant_id=? AND country_code=?");
  }

  public List<String> listIncidentTypes(Scope s) {
    return db
        .rows(
            s,
            "SELECT DISTINCT incident_type_code FROM coverage WHERE tenant_id=? AND country_code=?")
        .stream()
        .map(r -> r.get("incident_type_code").toString())
        .toList();
  }

  public Map<String, Object> listBenefits(Scope s, UUID policyId, String code) {
    return db.one(
        s,
        "SELECT c.* FROM coverage c JOIN policy p ON p.product_id=c.product_id AND"
            + " p.tenant_id=c.tenant_id AND p.country_code=c.country_code WHERE c.tenant_id=? AND"
            + " c.country_code=? AND p.id=? AND c.incident_type_code=?",
        policyId,
        code);
  }

  public boolean checkActive(Map<String, Object> p, LocalDate admission, LocalDate discharge) {
    LocalDate start = LocalDate.parse(p.get("start_date").toString()),
        end = LocalDate.parse(p.get("end_date").toString());
    return "ACTIVE".equals(p.get("status"))
        && !admission.isBefore(start)
        && !discharge.isAfter(end);
  }

  public String submitClaim(Scope s, UUID id) {
    return "RECEIVED";
  }

  public String getClaimStatus(Scope s, UUID id) {
    return db.one(
            s,
            "SELECT provider_claim_status FROM claim WHERE tenant_id=? AND country_code=? AND id=?",
            id)
        .get("provider_claim_status")
        .toString();
  }
}
