package com.sam.insurance;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/claims/{id}")
public class QualityController {
  private final CompletenessService quality;
  private final ClaimsService claims;
  private final Store db;

  public QualityController(CompletenessService quality, ClaimsService claims, Store db) {
    this.quality = quality;
    this.claims = claims;
    this.db = db;
  }

  @GetMapping("/completeness")
  @PreAuthorize("hasAuthority('claims:read')")
  public Map summary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return quality.summary(Scope.from(jwt), id);
  }

  @GetMapping("/ai-traces")
  @PreAuthorize("hasAuthority('claims:read')")
  public List traces(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    Scope s = Scope.from(jwt);
    claims.get(s, id);
    return db.rows(
        s,
        "SELECT id,metadata,created_at::text FROM ai_task_trace WHERE tenant_id=? AND"
            + " country_code=? AND claim_id=? ORDER BY created_at",
        id);
  }

  @PostMapping("/document-reviews")
  @Transactional
  @PreAuthorize("hasAuthority('claims:review')")
  public Map review(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody CompletenessService.Review input) {
    Scope s = Scope.from(jwt);
    Map result = quality.review(s, id, input);
    claims.refreshReadiness(s, id);
    return result;
  }

  @PostMapping("/document-waivers")
  @Transactional
  @PreAuthorize("hasAuthority('claims:approve')")
  public Map waive(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody CompletenessService.Waiver input) {
    Scope s = Scope.from(jwt);
    Map result = quality.waive(s, id, input);
    claims.refreshReadiness(s, id);
    return result;
  }
}
