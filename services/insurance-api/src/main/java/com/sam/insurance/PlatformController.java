package com.sam.insurance;

import static com.sam.insurance.PlatformContracts.*;
import static org.springframework.http.HttpStatus.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PlatformController {
  private final PlatformService platform;
  private final ChannelService channel;
  private final OperationsService operations;
  private final ExternalServices external;
  private final String callbackToken;

  public PlatformController(
      PlatformService platform,
      ChannelService channel,
      OperationsService operations,
      ExternalServices external,
      @Value("${app.provider-callback-token:}") String callbackToken) {
    this.platform = platform;
    this.channel = channel;
    this.operations = operations;
    this.external = external;
    this.callbackToken = callbackToken;
  }

  @PostMapping("/api/v1/intakes/from-text")
  @ResponseStatus(CREATED)
  @PreAuthorize("hasAuthority('claims:write')")
  public Contracts.ClaimView text(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody TextIntake input) {
    return platform.fromText(Scope.from(jwt), key, input);
  }

  @PostMapping("/api/v1/conversations/{id}/turns")
  @PreAuthorize("hasAuthority('claims:write')")
  public Map turn(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Turn input) {
    try {
      return external.conversation(Scope.from(jwt), id, input.text(), input.language());
    } catch (org.springframework.web.client.RestClientException ex) {
      throw new ResponseStatusException(BAD_GATEWAY, "Conversation unavailable");
    }
  }

  @PostMapping("/api/v1/claims/{id}/corrections")
  @PreAuthorize("hasAuthority('claims:write')")
  public Contracts.ClaimView correction(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Correction input) {
    return platform.correct(Scope.from(jwt), id, input);
  }

  @PostMapping("/api/v1/claims/{id}/evidence")
  @PreAuthorize("hasAuthority('claims:write')")
  public Map evidence(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Evidence input) {
    return platform.evidence(Scope.from(jwt), id, input);
  }

  @GetMapping("/api/v1/claims/{id}/evidence")
  @PreAuthorize("hasAuthority('claims:read')")
  public List evidenceList(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return platform.evidenceList(Scope.from(jwt), id);
  }

  @PostMapping("/api/v1/claims/{id}/evidence/{evidence}/resolutions")
  @PreAuthorize("hasAuthority('claims:review')")
  public Map resolve(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @PathVariable UUID evidence,
      @Valid @RequestBody Resolution input) {
    return platform.resolveEvidence(Scope.from(jwt), id, evidence, input);
  }

  @PostMapping("/api/v1/claims/{id}/rejections")
  @PreAuthorize("hasAuthority('claims:approve')")
  public Contracts.ClaimView reject(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Contracts.Approval input) {
    return platform.reject(Scope.from(jwt), id, input);
  }

  @PostMapping("/api/v1/claims/{id}/provider-submissions")
  @PreAuthorize("hasAuthority('claims:write')")
  public Contracts.ClaimView submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return platform.providerSubmit(Scope.from(jwt), id);
  }

  @PostMapping("/api/v1/claims/{id}/provider-reconciliations")
  @PreAuthorize("hasAuthority('claims:review')")
  public Contracts.ClaimView reconcile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return platform.reconcileProvider(Scope.from(jwt), id);
  }

  @PostMapping("/api/v1/claims/{id}/mock-provider-receipts")
  @PreAuthorize("hasAuthority('claims:pay')")
  public Map receipt(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Receipt input) {
    return platform.paymentReceipt(Scope.from(jwt), id, input);
  }

  @PostMapping("/api/v1/claims/{id}/payout-reconciliations")
  @PreAuthorize("hasAuthority('claims:pay')")
  public Contracts.ClaimView paymentReconcile(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return platform.reconcilePayment(Scope.from(jwt), id);
  }

  @PostMapping("/webhooks/turaco-simulator")
  public Map callback(
      @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
      @Valid @RequestBody ProviderEvent input) {
    if (callbackToken.isBlank()
        || !MessageDigest.isEqual(
            ("Bearer " + callbackToken).getBytes(StandardCharsets.UTF_8),
            authorization.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(UNAUTHORIZED, "Provider callback authentication required");
    return platform.providerEvent(input);
  }

  @PostMapping("/webhooks/whatsapp")
  public Map webhook(
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
      @RequestBody String raw) {
    return channel.receive(raw, signature);
  }

  @GetMapping("/webhooks/whatsapp")
  public String verify(
      @RequestParam("hub.mode") String mode,
      @RequestParam("hub.verify_token") String token,
      @RequestParam("hub.challenge") String challenge) {
    return channel.challenge(mode, token, challenge);
  }

  @GetMapping("/api/v1/channels/whatsapp/messages")
  @PreAuthorize("hasAuthority('claims:read')")
  public List messages(@AuthenticationPrincipal Jwt jwt) {
    return channel.list(Scope.from(jwt));
  }

  public record Process(
      @NotNull UUID policy_id,
      @Pattern(regexp = "en-KE|sw-KE") @NotNull String language,
      @Size(max = 20000) String fixture_transcript) {}

  @PostMapping("/api/v1/channels/whatsapp/messages/{id}/intakes")
  @PreAuthorize("hasAuthority('claims:write')")
  public Contracts.ClaimView process(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Process input) {
    return channel.process(
        Scope.from(jwt), id, input.policy_id(), input.language(), input.fixture_transcript());
  }

  @PostMapping("/api/v1/notifications/previews")
  @PreAuthorize("hasAuthority('claims:read')")
  public Map notify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Notification input) {
    return channel.notification(Scope.from(jwt), input);
  }

  @GetMapping("/api/v1/operations/metrics")
  @PreAuthorize("hasAuthority('claims:review')")
  public Map metrics(@AuthenticationPrincipal Jwt jwt) {
    return operations.metrics(Scope.from(jwt));
  }

  @PostMapping("/api/v1/operations/drain")
  @PreAuthorize("hasAuthority('claims:approve')")
  public Map drain(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "false") boolean simulateFailure) {
    return operations.drain(Scope.from(jwt), simulateFailure);
  }
}
