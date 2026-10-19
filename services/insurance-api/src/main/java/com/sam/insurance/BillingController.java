package com.sam.insurance;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing/batches")
public class BillingController {
  private final BillingService service;

  public BillingController(BillingService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('claims:read')")
  public List list(@AuthenticationPrincipal Jwt jwt) {
    return service.list(Scope.from(jwt));
  }

  @GetMapping("/metrics")
  @PreAuthorize("hasAuthority('claims:review')")
  public Map metrics(@AuthenticationPrincipal Jwt jwt) {
    return service.metrics(Scope.from(jwt));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('claims:read')")
  public Map get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.get(Scope.from(jwt), id);
  }

  @PostMapping
  @PreAuthorize("hasAuthority('claims:write')")
  public Map upload(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody BillingService.Upload input) {
    return service.upload(Scope.from(jwt), key, input);
  }

  @PostMapping("/{id}/mapping-reviews")
  @PreAuthorize("hasAuthority('claims:review')")
  public Map mapping(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody BillingService.MappingReview input) {
    return service.reviewMapping(Scope.from(jwt), id, input);
  }

  @PostMapping("/{id}/approvals")
  @PreAuthorize("hasAuthority('claims:approve')")
  public Map approve(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody BillingService.Approval input) {
    return service.approve(Scope.from(jwt), id, input);
  }

  @PostMapping("/{id}/invoice-preparations")
  @PreAuthorize("hasAuthority('claims:approve')")
  public Map invoice(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Contracts.Approval input) {
    return service.prepare(Scope.from(jwt), id, input);
  }

  @PostMapping("/{id}/resumptions")
  @PreAuthorize("hasAuthority('claims:write')")
  public Map resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.resume(Scope.from(jwt), id);
  }

  @PostMapping("/{id}/cancellations")
  @PreAuthorize("hasAuthority('claims:approve')")
  public Map cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Contracts.Approval input) {
    return service.cancel(Scope.from(jwt), id, input);
  }
}
