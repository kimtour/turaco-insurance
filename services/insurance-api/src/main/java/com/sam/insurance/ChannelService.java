package com.sam.insurance;

import static org.springframework.http.HttpStatus.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChannelService {
  private final Store db;
  private final EncryptedPayloads encryption;
  private final PlatformService platform;
  private final String secret, verifyToken;
  private final UUID tenant;

  public ChannelService(
      Store db,
      EncryptedPayloads encryption,
      PlatformService platform,
      @Value("${app.whatsapp-secret:}") String secret,
      @Value("${app.whatsapp-verify-token:}") String verifyToken,
      @Value("${app.whatsapp-tenant:11111111-1111-1111-1111-111111111111}") UUID tenant) {
    this.db = db;
    this.encryption = encryption;
    this.platform = platform;
    this.secret = secret;
    this.verifyToken = verifyToken;
    this.tenant = tenant;
  }

  public String challenge(String mode, String token, String challenge) {
    if (verifyToken.isBlank()
        || !"subscribe".equals(mode)
        || !MessageDigest.isEqual(
            verifyToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(FORBIDDEN, "Webhook verification rejected");
    return challenge;
  }

  @Transactional
  public Map receive(String raw, String signature) {
    try {
      return receiveEnvelope(raw, signature);
    } catch (ClassCastException | NullPointerException ex) {
      throw new ResponseStatusException(BAD_REQUEST, "Malformed WhatsApp envelope");
    }
  }

  private Map receiveEnvelope(String raw, String signature) {
    if (raw.length() > 100000)
      throw new ResponseStatusException(PAYLOAD_TOO_LARGE, "Webhook too large");
    try {
      if (secret.isBlank())
        throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Webhook not configured");
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String expected =
          "sha256=" + HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
      if (signature == null
          || !MessageDigest.isEqual(
              expected.getBytes(StandardCharsets.UTF_8),
              signature.getBytes(StandardCharsets.UTF_8)))
        throw new ResponseStatusException(UNAUTHORIZED, "Webhook signature rejected");
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException(ex);
    }
    Map envelope = db.parse(raw, Map.class);
    Scope scope = new Scope(tenant, "KE", "verified-whatsapp-ingress");
    int accepted = 0, duplicates = 0;
    Object entries = envelope.get("entry");
    if (!(entries instanceof List<?> list))
      throw new ResponseStatusException(BAD_REQUEST, "Expected WhatsApp entry envelope");
    int count = 0;
    for (Object entry : list) {
      for (Object change : (List<?>) ((Map) entry).getOrDefault("changes", List.of())) {
        Map value = (Map) ((Map) change).get("value");
        if (value == null) continue;
        Map metadata = (Map) value.get("metadata");
        if (metadata == null || !"SYNTHETIC_NUMBER".equals(metadata.get("phone_number_id")))
          throw new ResponseStatusException(BAD_REQUEST, "Unconfigured phone-number route");
        for (Object item : (List<?>) value.getOrDefault("messages", List.of())) {
          if (++count > 50)
            throw new ResponseStatusException(PAYLOAD_TOO_LARGE, "Too many messages");
          Map message = (Map) item;
          String id = Objects.toString(message.get("id"), "");
          if (id.isBlank() || id.length() > 200)
            throw new ResponseStatusException(BAD_REQUEST, "Missing provider message ID");
          String payload = db.json(message), digest = EncryptedPayloads.digest(payload);
          UUID receipt = UUID.randomUUID();
          int inserted =
              db.update(
                  scope,
                  "INSERT INTO"
                      + " channel_receipt(tenant_id,country_code,id,message_id,digest,ciphertext)"
                      + " VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,country_code,message_id) DO"
                      + " NOTHING",
                  receipt,
                  id,
                  digest,
                  encryption.encrypt(payload, EncryptedPayloads.context(scope, receipt)));
          if (inserted == 0) {
            Map old =
                db.one(
                    scope,
                    "SELECT digest FROM channel_receipt WHERE tenant_id=? AND country_code=? AND"
                        + " message_id=?",
                    id);
            if (!digest.equals(old.get("digest")))
              throw new ResponseStatusException(
                  CONFLICT, "Message ID reused with different content");
            duplicates++;
          } else {
            accepted++;
            db.audit(
                scope,
                receipt,
                "CHANNEL_MESSAGE_RECEIVED",
                Map.of(
                    "receipt_id",
                    receipt,
                    "type",
                    Objects.toString(message.get("type"), "unknown")),
                null);
          }
        }
      }
    }
    return Map.of("accepted", accepted, "duplicates", duplicates, "durable", true);
  }

  public List<Map<String, Object>> list(Scope s) {
    return db.rows(
        s,
        "SELECT id,message_id,status,claim_id,created_at::text FROM channel_receipt WHERE"
            + " tenant_id=? AND country_code=? ORDER BY created_at DESC LIMIT 100");
  }

  @Transactional
  public Contracts.ClaimView process(
      Scope s, UUID receipt, UUID policy, String language, String fixtureTranscript) {
    Map row =
        db.one(
            s,
            "SELECT * FROM channel_receipt WHERE tenant_id=? AND country_code=? AND id=? FOR"
                + " UPDATE",
            receipt);
    if (row.get("claim_id") != null) return platformClaim(s, (UUID) row.get("claim_id"));
    Map message =
        db.parse(
            encryption.decrypt(
                (byte[]) row.get("ciphertext"), EncryptedPayloads.context(s, receipt)),
            Map.class);
    String type = Objects.toString(message.get("type"), "");
    String text;
    if ("text".equals(type))
      text = Objects.toString(((Map) message.getOrDefault("text", Map.of())).get("body"), "");
    else {
      if (fixtureTranscript == null || fixtureTranscript.isBlank())
        throw new ResponseStatusException(
            CONFLICT,
            "Unsupported media requires human assistance or an explicit synthetic transcript; no"
                + " ASR result is fabricated");
      text = fixtureTranscript;
    }
    if (text.isBlank() || text.length() > 20000)
      throw new ResponseStatusException(BAD_REQUEST, "Invalid text intake");
    var claim =
        platform.fromText(
            s, "whatsapp:" + receipt, new PlatformContracts.TextIntake(policy, text, language));
    db.update(
        s,
        "UPDATE channel_receipt SET claim_id=v.claim,status='PROCESSED' FROM(SELECT ?::uuid"
            + " t,?::text c,?::uuid claim)v WHERE tenant_id=v.t AND country_code=v.c AND id=?",
        claim.id(),
        receipt);
    db.audit(
        s,
        claim.id(),
        "CHANNEL_INTAKE_LINKED",
        Map.of(
            "receipt_id",
            receipt,
            "modality",
            type,
            "speech_engine",
            "text".equals(type) ? "none" : "SYNTHETIC_TRANSCRIPT_ONLY"),
        null);
    return claim;
  }

  private Contracts.ClaimView platformClaim(Scope s, UUID id) {
    return platform.existing(s, id);
  }

  public Map notification(Scope s, PlatformContracts.Notification input) {
    platform.existing(s, input.claim_id());
    if (input.last_customer_message().isAfter(Instant.now()))
      throw new ResponseStatusException(
          BAD_REQUEST, "Last customer message cannot be in the future");
    if (input.opted_out()) return Map.of("status", "SUPPRESSED", "live", false);
    boolean window =
        input.last_customer_message().isAfter(Instant.now().minus(Duration.ofHours(24)));
    return Map.of(
        "status",
        "PREVIEW_ONLY",
        "mode",
        window ? "SERVICE_WINDOW" : "LOCAL_TEMPLATE_SIMULATOR",
        "template",
        input.template_key(),
        "language",
        input.language(),
        "version",
        "synthetic-v1",
        "live",
        false,
        "real_template_approval",
        false);
  }
}
