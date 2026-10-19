package com.sam.insurance;

import static org.springframework.http.HttpStatus.*;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Future-channel rehearsal. No messaging, ASR or telephony client exists. */
@Service
public class ChannelSimulationService {
  private final Store db;
  private final ClaimsService claims;
  private final CompletenessService quality;

  public ChannelSimulationService(Store db, ClaimsService claims, CompletenessService quality) {
    this.db = db;
    this.claims = claims;
    this.quality = quality;
  }

  public record Notification(
      @NotNull UUID claim_id,
      @NotBlank @Pattern(regexp = "en-KE|sw-KE") String language,
      @NotBlank @Pattern(regexp = "missing_documents|claim_updated") String template_key,
      boolean consent,
      boolean opted_out,
      @NotNull Instant last_customer_message,
      @NotBlank @Size(max = 200) String evidence_reference) {}

  public record Voice(
      @NotNull UUID claim_id,
      @NotBlank @Pattern(regexp = "en-KE|sw-KE") String language,
      @NotBlank @Size(max = 2000) String synthetic_transcript) {}

  @Transactional
  public Map notification(Scope s, String key, Notification input) {
    BillingService.require(
        key != null && key.matches("[A-Za-z0-9:_-]{1,120}"), "A valid Idempotency-Key is required");
    claims.get(s, input.claim_id());
    db.rows(
        s,
        "SELECT"
            + " pg_advisory_xact_lock(hashtextextended(?::text||':'||?::text||':notification:'||?,0))",
        key);
    String digest = EncryptedPayloads.digest(db.json(input));
    var old =
        db.rows(
            s,
            "SELECT id,status,request_digest FROM notification_simulation WHERE tenant_id=? AND"
                + " country_code=? AND client_key=?",
            key);
    if (!old.isEmpty()) {
      BillingService.require(
          digest.equals(old.getFirst().get("request_digest")),
          "Notification key reused with different input");
      return Map.of(
          "id", old.getFirst().get("id"), "status", old.getFirst().get("status"), "live", false);
    }
    Instant now = Instant.now();
    if (input.last_customer_message().isAfter(now))
      throw new ResponseStatusException(BAD_REQUEST, "Customer message cannot be in the future");
    var claim = claims.get(s, input.claim_id());
    int hour = now.atZone(zone(s.country())).getHour();
    boolean quiet = hour < 8 || hour >= 20;
    boolean throttled =
        !db.rows(
                s,
                "SELECT id FROM notification_simulation WHERE tenant_id=? AND country_code=? AND"
                    + " claim_id=? AND status='PREVIEW_APPROVED' AND created_at>now()-interval '24"
                    + " hours'",
                input.claim_id())
            .isEmpty();
    String status =
        !input.consent() || input.opted_out()
            ? "SUPPRESSED_CONSENT"
            : !claim.claimant_verified()
                ? "REQUIRES_IDENTITY"
                : quiet
                    ? "DEFERRED_QUIET_HOURS"
                    : throttled ? "SUPPRESSED_THROTTLE" : "PREVIEW_APPROVED";
    UUID id = UUID.randomUUID();
    db.update(
        s,
        "INSERT INTO"
            + " notification_simulation(tenant_id,country_code,id,claim_id,client_key,request_digest,status,template_key,language,actor,evidence_reference)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        id,
        input.claim_id(),
        key,
        digest,
        status,
        input.template_key(),
        input.language(),
        s.actor(),
        input.evidence_reference());
    db.audit(
        s,
        input.claim_id(),
        "NOTIFICATION_SIMULATED",
        Map.of(
            "simulation_id",
            id,
            "status",
            status,
            "evidence_reference",
            input.evidence_reference()),
        null);
    Map result =
        new LinkedHashMap(
            Map.of(
                "id",
                id,
                "status",
                status,
                "live",
                false,
                "template_required",
                input.last_customer_message().isBefore(now.minusSeconds(86400)),
                "template_version",
                "synthetic-v1",
                "quiet_hours",
                "20:00–08:00 local",
                "language",
                input.language()));
    if ("PREVIEW_APPROVED".equals(status))
      result.put(
          "preview",
          input.template_key().equals("missing_documents")
              ? quality.summary(s, input.claim_id()).get("follow_up")
              : "Your synthetic claim status is " + claim.provider_claim_status() + ".");
    return result;
  }

  static ZoneId zone(String country) {
    return ZoneId.of(
        Map.of(
                "KE",
                "Africa/Nairobi",
                "UG",
                "Africa/Kampala",
                "NG",
                "Africa/Lagos",
                "GH",
                "Africa/Accra",
                "ZM",
                "Africa/Lusaka")
            .get(country));
  }

  public Map summary(Scope s, UUID id) {
    var c = claims.get(s, id);
    BillingService.require(
        c.claimant_verified(), "Verified identity is required for cross-channel context");
    return Map.of(
        "claim_id",
        id,
        "intake_status",
        c.intake_status(),
        "claim_status",
        c.provider_claim_status(),
        "payout_status",
        c.payout_status(),
        "missing_documents",
        quality.summary(s, id).get("missing_documents"),
        "source",
        "authoritative_scoped_snapshot",
        "generated_at",
        Instant.now().toString(),
        "live",
        false);
  }

  public Map voice(Scope s, Voice input) {
    var context = summary(s, input.claim_id());
    String text = input.synthetic_transcript().toLowerCase(Locale.ROOT);
    String reply;
    if (text.matches(".*(pay|approve|transfer|idhinisha|lipa).*"))
      reply = "An authorized human must review decisions and simulated payments.";
    else if (text.matches(".*(document|claim|hati|dai).*"))
      reply = quality.summary(s, input.claim_id()).get("follow_up").toString();
    else if (text.matches(".*(cover|benefit|bima|faida).*")) {
      var terms =
          db.one(
              s,
              "SELECT"
                  + " v.daily_amount,v.currency,v.max_nights,v.waiting_days,p.start_date::text,p.end_date::text,p.status"
                  + " FROM claim c JOIN policy p ON p.id=c.policy_id AND p.tenant_id=c.tenant_id"
                  + " AND p.country_code=c.country_code JOIN coverage v ON"
                  + " v.product_id=p.product_id AND v.tenant_id=p.tenant_id AND"
                  + " v.country_code=p.country_code AND v.incident_type_code='HOSPITALIZATION'"
                  + " WHERE c.tenant_id=? AND c.country_code=? AND c.id=?",
              input.claim_id());
      reply =
          "Synthetic Hospital Cash terms: "
              + terms.get("currency")
              + " "
              + terms.get("daily_amount")
              + " per covered night, maximum "
              + terms.get("max_nights")
              + " nights; waiting period "
              + terms.get("waiting_days")
              + " days. Policy dates "
              + terms.get("start_date")
              + " to "
              + terms.get("end_date")
              + ", status "
              + terms.get("status")
              + ". A human must assess eligibility.";
    } else
      reply =
          input.language().equals("sw-KE")
              ? "Huu ni mfano wa maandishi. Mhudumu atakusaidia kuhusu bima au dai lako."
              : "This is a text simulation. An authorized person can help with coverage and"
                    + " onboarding questions.";
    return Map.of(
        "mode",
        "SYNTHETIC_TRANSCRIPT_ONLY",
        "asr_enabled",
        false,
        "tts_enabled",
        false,
        "human_handoff",
        true,
        "reply",
        reply,
        "context",
        context,
        "language",
        input.language(),
        "live",
        false);
  }
}
