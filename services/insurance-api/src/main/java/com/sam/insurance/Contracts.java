package com.sam.insurance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class Contracts {
  private Contracts() {}

  public enum FieldStatus {
    extracted,
    confirmed,
    unverified,
    missing
  }

  public record Extracted<T>(
      T value,
      @NotNull FieldStatus status,
      @DecimalMin("0") @DecimalMax("1") Double confidence,
      @Size(max = 2000) String evidence_span,
      @Size(max = 200) String source) {}

  public record Extraction(
      @NotBlank @Pattern(regexp = "2") String schema_version,
      @NotBlank @Pattern(regexp = "en-KE|sw-KE") String language,
      @NotNull @Valid Extracted<String> incident_type_code,
      @NotNull @Valid Extracted<String> hospital_name,
      @NotNull @Valid Extracted<LocalDate> admission_date,
      @NotNull @Valid Extracted<LocalDate> discharge_date,
      @NotNull @Valid Extracted<Integer> hospital_nights,
      boolean requires_confirmation,
      Map<String, String> model_trace) {
    public Extraction(
        String schema,
        String language,
        Extracted<String> incident,
        Extracted<String> hospital,
        Extracted<LocalDate> admission,
        Extracted<LocalDate> discharge,
        Extracted<Integer> nights,
        boolean confirmation) {
      this(schema, language, incident, hospital, admission, discharge, nights, confirmation, null);
    }
  }

  public record CreateClaim(@NotNull UUID policy_id, @NotNull @Valid Extraction extraction) {}

  public record Confirmation(
      @NotEmpty Set<String> fields, @NotBlank @Size(max = 200) String evidence_reference) {}

  public record Verification(
      @NotBlank @Size(max = 200) String evidence_reference, boolean payee_verified) {}

  public record Review(
      @NotBlank @Pattern(regexp = "RECOMMEND_APPROVAL|REQUEST_INFORMATION|RECOMMEND_REJECTION")
          String decision,
      @NotBlank @Size(max = 100) String reason_code,
      @NotBlank @Size(max = 200) String evidence_reference) {}

  public record Approval(@NotBlank @Size(max = 200) String evidence_reference) {}

  public record Payment(@NotBlank @Pattern(regexp = "CONFIRMED|UNKNOWN") String outcome) {}

  public record CustomerInput(
      @NotBlank @Size(max = 100) String full_name,
      @NotBlank @Pattern(regexp = "\\+[1-9][0-9]{7,14}") String mobile_number) {}

  public record PolicyInput(
      @NotNull UUID customer_id,
      @NotBlank @Size(max = 100) String policy_number,
      @NotNull LocalDate start_date,
      @NotNull LocalDate end_date,
      @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal premium) {}

  public record ClaimView(
      UUID id,
      UUID policy_id,
      String intake_status,
      String provider_claim_status,
      String payout_status,
      boolean claimant_verified,
      boolean payee_verified,
      Extraction extraction,
      UUID obligation_id,
      BigDecimal amount,
      String currency,
      String provider_kind,
      long provider_revision) {}
}
