package com.sam.insurance;

import static org.springframework.http.HttpStatus.CONFLICT;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OperationsController {
  private final RiskService risk;
  private final Store db;

  public OperationsController(RiskService risk, Store db) {
    this.risk = risk;
    this.db = db;
  }

  @PostMapping("/api/v1/claims/{id}/risk-assessments")
  @PreAuthorize("hasAuthority('claims:review')")
  public Map assess(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return risk.assess(Scope.from(jwt), id);
  }

  @GetMapping("/api/v1/operations/dead-letters")
  @PreAuthorize("hasAuthority('claims:review')")
  public List dead(@AuthenticationPrincipal Jwt jwt) {
    return db.rows(
        Scope.from(jwt),
        "SELECT id,event_id,attempts,created_at::text FROM queue_delivery WHERE tenant_id=? AND"
            + " country_code=? AND status='DEAD' ORDER BY created_at LIMIT 100");
  }

  @PostMapping("/api/v1/operations/dead-letters/{id}/replays")
  @Transactional
  @PreAuthorize("hasAuthority('claims:approve')")
  public Map replay(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Contracts.Approval input) {
    Scope s = Scope.from(jwt);
    Map row =
        db.one(
            s,
            "SELECT status FROM queue_delivery WHERE tenant_id=? AND country_code=? AND id=? FOR"
                + " UPDATE",
            id);
    if (!"DEAD".equals(row.get("status")))
      throw new ResponseStatusException(CONFLICT, "Only a dead letter can be replayed");
    db.update(
        s,
        "UPDATE queue_delivery SET attempts=0,status='READY',available_at=now() WHERE tenant_id=?"
            + " AND country_code=? AND id=?",
        id);
    db.audit(
        s,
        id,
        "DEAD_LETTER_REPLAY_AUTHORIZED",
        Map.of("evidence_reference", input.evidence_reference()),
        null);
    return Map.of("status", "READY");
  }
}
