package com.sam.insurance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class PlatformContracts {
  private PlatformContracts() {}

  public record TextIntake(
      @NotNull UUID policy_id,
      @NotBlank @Size(max = 20000) String text,
      @NotBlank @Pattern(regexp = "en-KE|sw-KE") String language) {}

  public record Turn(
      @NotBlank @Size(max = 20000) String text,
      @NotBlank @Pattern(regexp = "en-KE|sw-KE") String language) {}

  public record Correction(
      @NotNull @Valid Contracts.Extraction extraction,
      @NotBlank @Size(max = 200) String evidence_reference) {}

  public record Evidence(@NotBlank @Size(max = 20000) String synthetic_text) {}

  public record Resolution(
      @NotNull UUID replacement_id, @NotBlank @Size(max = 200) String evidence_reference) {}

  public record ProviderEvent(
      @NotNull UUID tenant_id,
      @NotBlank @Pattern(regexp = "KE") String country_code,
      @NotNull UUID internal_claim_id,
      @NotNull UUID event_id,
      @Min(1) long revision) {}

  public record Receipt(
      @NotBlank @Pattern(regexp = "CONFIRMED|FAILED") String outcome,
      @NotBlank @Size(max = 200) String evidence_reference) {}

  public record Notification(
      @NotNull UUID claim_id,
      @NotBlank @Pattern(regexp = "en-KE|sw-KE") String language,
      @NotBlank @Pattern(regexp = "claim_received|claim_updated") String template_key,
      @NotNull java.time.Instant last_customer_message,
      boolean opted_out) {}
}
