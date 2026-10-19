package com.sam.insurance;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ChannelSimulationController {
  private final ChannelSimulationService service;

  public ChannelSimulationController(ChannelSimulationService service) {
    this.service = service;
  }

  @PostMapping("/notifications/simulations")
  @PreAuthorize("hasAuthority('claims:approve')")
  public Map notify(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ChannelSimulationService.Notification input) {
    return service.notification(Scope.from(jwt), key, input);
  }

  @PostMapping("/voice/simulations")
  @PreAuthorize("hasAuthority('claims:write')")
  public Map voice(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChannelSimulationService.Voice input) {
    return service.voice(Scope.from(jwt), input);
  }

  @GetMapping("/claims/{id}/channel-summary")
  @PreAuthorize("hasAuthority('claims:read')")
  public Map summary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.summary(Scope.from(jwt), id);
  }
}
