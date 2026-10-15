package com.sam.insurance;

import static com.sam.insurance.Contracts.*;
import static org.springframework.http.HttpStatus.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
@Validated
public class CatalogController {
  private final Store db;
  private final StandaloneAdapter adapter;

  public CatalogController(Store db, StandaloneAdapter adapter) {
    this.db = db;
    this.adapter = adapter;
  }

  @GetMapping("/catalog")
  @PreAuthorize("hasAuthority('claims:read')")
  public Map<String, Object> catalog(@AuthenticationPrincipal Jwt jwt) {
    Scope s = Scope.from(jwt);
    return Map.of(
        "mode",
        "standalone",
        "products",
        adapter.listProducts(s),
        "incident_types",
        adapter.listIncidentTypes(s),
        "payments",
        "simulated_only");
  }

  @PostMapping("/customers")
  @ResponseStatus(CREATED)
  @PreAuthorize("hasAuthority('claims:write')")
  public Map<String, UUID> customer(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CustomerInput input) {
    return Map.of("id", adapter.createCustomer(Scope.from(jwt), input));
  }

  @GetMapping("/policies")
  @PreAuthorize("hasAuthority('claims:read')")
  public List<Map<String, Object>> policies(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return db.rows(
        Scope.from(jwt),
        "SELECT"
            + " id,policy_number,status,start_date::text,end_date::text,currency,premium,customer_id"
            + " FROM policy WHERE tenant_id=? AND country_code=? ORDER BY policy_number LIMIT ?"
            + " OFFSET ?",
        size,
        page * size);
  }

  @GetMapping("/policies/{id}")
  @PreAuthorize("hasAuthority('claims:read')")
  public Map<String, Object> policy(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return db.one(
        Scope.from(jwt),
        "SELECT"
            + " id,policy_number,status,start_date::text,end_date::text,currency,premium,customer_id"
            + " FROM policy WHERE tenant_id=? AND country_code=? AND id=?",
        id);
  }

  @PostMapping("/policies")
  @ResponseStatus(CREATED)
  @Transactional
  @PreAuthorize("hasAuthority('claims:write')")
  public Map<String, UUID> createPolicy(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PolicyInput input) {
    Scope s = Scope.from(jwt);
    adapter.findCustomer(s, input.customer_id());
    if (!input.end_date().isAfter(input.start_date()))
      throw new ResponseStatusException(BAD_REQUEST, "Policy end must follow start");
    Map<String, Object> b =
        db.one(
            s,
            "SELECT product_id,currency FROM coverage WHERE tenant_id=? AND country_code=? AND"
                + " incident_type_code='HOSPITALIZATION'");
    UUID id = UUID.randomUUID();
    db.update(
        s,
        "INSERT INTO"
            + " policy(tenant_id,country_code,id,customer_id,product_id,policy_number,start_date,end_date,premium,currency,status)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,'ACTIVE')",
        id,
        input.customer_id(),
        b.get("product_id"),
        input.policy_number(),
        input.start_date(),
        input.end_date(),
        input.premium(),
        b.get("currency"));
    db.audit(s, id, "POLICY_CREATED", Map.of(), 1);
    return Map.of("id", id);
  }

  @PostMapping("/policies/{id}/cancellations")
  @Transactional
  @PreAuthorize("hasAuthority('claims:approve')")
  public Map<String, String> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    Scope s = Scope.from(jwt);
    adapter.findPolicy(s, id);
    db.update(
        s,
        "UPDATE policy SET status='CANCELLED',version=version+1 WHERE tenant_id=? AND"
            + " country_code=? AND id=? AND status='ACTIVE'",
        id);
    db.audit(s, id, "POLICY_CANCELLED", Map.of(), null);
    return Map.of("status", "CANCELLED");
  }
}
