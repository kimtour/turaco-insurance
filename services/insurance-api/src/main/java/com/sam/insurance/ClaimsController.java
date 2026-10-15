package com.sam.insurance;

import static com.sam.insurance.Contracts.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/claims")
@Validated
public class ClaimsController {
  private final ClaimsService service;

  public ClaimsController(ClaimsService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('claims:write')")
  public ClaimView create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreateClaim input) {
    return service.create(Scope.from(jwt), key, input);
  }

  @GetMapping
  @PreAuthorize("hasAuthority('claims:read')")
  public List<ClaimView> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.list(Scope.from(jwt), page, size);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('claims:read')")
  public ClaimView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.get(Scope.from(jwt), id);
  }

  @PostMapping("/{id}/confirmations")
  @PreAuthorize("hasAuthority('claims:write')")
  public ClaimView confirm(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Confirmation input) {
    return service.confirm(Scope.from(jwt), id, input);
  }

  @PostMapping("/{id}/verifications")
  @PreAuthorize("hasAuthority('claims:review')")
  public ClaimView verify(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Verification input) {
    return service.verify(Scope.from(jwt), id, input);
  }

  @PostMapping("/{id}/submissions")
  @PreAuthorize("hasAuthority('claims:write')")
  public ClaimView submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.submit(Scope.from(jwt), id);
  }

  @PostMapping("/{id}/reviews")
  @PreAuthorize("hasAuthority('claims:review')")
  public ClaimView review(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Review input) {
    return service.review(Scope.from(jwt), id, input);
  }

  @PostMapping("/{id}/approvals")
  @PreAuthorize("hasAuthority('claims:approve')")
  public ClaimView approve(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Approval input) {
    return service.approve(Scope.from(jwt), id, input);
  }

  @PostMapping("/{id}/simulated-payouts")
  @PreAuthorize("hasAuthority('claims:pay')")
  public ClaimView simulate(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Payment input) {
    return service.simulate(Scope.from(jwt), id, input);
  }

  @GetMapping("/{id}/audit")
  @PreAuthorize("hasAuthority('claims:read')")
  public List<Map<String, Object>> audit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.audit(Scope.from(jwt), id);
  }
}
