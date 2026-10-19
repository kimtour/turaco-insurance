package com.sam.insurance;

import static org.springframework.http.HttpStatus.*;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BillingService {
  static final Set<String> TARGETS =
      Set.of("customer_reference", "policy_number", "premium", "currency");
  private final Store db;
  private final EncryptedPayloads encryption;
  private final ExternalServices external;

  public BillingService(Store db, EncryptedPayloads encryption, ExternalServices external) {
    this.db = db;
    this.encryption = encryption;
    this.external = external;
  }

  public record Upload(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,60}") String partner_code,
      @NotBlank @Pattern(regexp = "[0-9]{4}-[0-9]{2}") String period,
      @NotBlank @Size(max = 120) String filename,
      @NotBlank @Size(max = 200000) String csv) {}

  public record MappingReview(
      @NotNull Map<String, String> mapping, @NotBlank @Size(max = 200) String evidence_reference) {}

  public record Approval(
      @NotNull Set<Integer> excluded_rows, @NotBlank @Size(max = 200) String evidence_reference) {}

  static void require(boolean ok, String message) {
    if (!ok) throw new ResponseStatusException(CONFLICT, message);
  }

  Map<String, Object> row(Scope s, UUID id) {
    return db.one(
        s,
        "SELECT * FROM partner_batch WHERE tenant_id=? AND country_code=? AND id=? FOR UPDATE",
        id);
  }

  void stage(Scope s, UUID id, String state, String reference) {
    db.update(
        s,
        "UPDATE partner_batch SET stage=v.state,updated_at=clock_timestamp() FROM(SELECT ?::uuid"
            + " t,?::text c,?::text state)v WHERE tenant_id=v.t AND country_code=v.c AND id=?",
        state,
        id);
    db.update(
        s,
        "INSERT INTO"
            + " billing_transition(tenant_id,country_code,id,batch_id,stage,actor,evidence_reference)"
            + " VALUES(?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        state,
        s.actor(),
        reference);
    db.audit(
        s,
        id,
        "BILLING_" + state,
        reference == null ? Map.of() : Map.of("evidence_reference", reference),
        null);
  }

  public List<Map<String, Object>> list(Scope s) {
    return db.rows(
        s,
        "SELECT id,partner_code,billing_period,filename,stage,created_at::text,updated_at::text"
            + " FROM partner_batch WHERE tenant_id=? AND country_code=? ORDER BY created_at DESC"
            + " LIMIT 100");
  }

  public Map<String, Object> metrics(Scope s) {
    var result =
        db.one(
            s,
            "SELECT count(*) batches,count(*) FILTER(WHERE stage='COMPLETED') completed,count(*)"
                + " FILTER(WHERE stage='MAPPING_REVIEW') mapping_reviews,count(*) FILTER(WHERE"
                + " stage='EXCEPTIONS_REVIEW') exception_reviews,count(*) FILTER(WHERE"
                + " stage='FAILED_RETRYABLE') retryable_failures,avg(extract(epoch"
                + " FROM(updated_at-created_at))) FILTER(WHERE stage='COMPLETED')"
                + " mean_receipt_to_invoice_seconds FROM partner_batch WHERE tenant_id=? AND"
                + " country_code=?");
    result.put(
        "manual_interventions",
        db.one(
                s,
                "SELECT count(*) total FROM billing_transition WHERE tenant_id=? AND country_code=?"
                    + " AND evidence_reference IS NOT NULL AND stage IN"
                    + " ('TRANSFORMED','APPROVED','INVOICE_PREPARED','CANCELLED')")
            .get("total"));
    result.put("scope", "synthetic_observations_only");
    return result;
  }

  public Map<String, Object> get(Scope s, UUID id) {
    Map<String, Object> r =
        db.one(
            s,
            "SELECT"
                + " id,partner_code,billing_period,filename,stage,profile,mapping,validation,excluded_rows,model_trace,created_at::text,updated_at::text,extract(epoch"
                + " FROM(updated_at-created_at)) elapsed_seconds FROM partner_batch WHERE"
                + " tenant_id=? AND country_code=? AND id=?",
            id);
    for (String key : List.of("profile", "mapping", "validation", "excluded_rows", "model_trace"))
      r.put(key, db.parse(r.get(key), Object.class));
    r.put(
        "timeline",
        db.rows(
            s,
            "SELECT stage,actor,evidence_reference,created_at::text,extract(epoch"
                + " FROM(created_at-lag(created_at) OVER(ORDER BY sequence))) stage_seconds FROM"
                + " billing_transition WHERE tenant_id=? AND country_code=? AND batch_id=? ORDER BY"
                + " sequence",
            id));
    r.put(
        "invoices",
        db.rows(
            s,
            "SELECT id,amount,currency,record_count,created_at::text FROM simulated_invoice WHERE"
                + " tenant_id=? AND country_code=? AND batch_id=?",
            id));
    r.put("simulated", true);
    return r;
  }

  @Transactional
  public Map<String, Object> upload(Scope s, String key, Upload input) {
    require(
        key != null && key.matches("[A-Za-z0-9:_-]{1,120}"), "A valid Idempotency-Key is required");
    try {
      YearMonth.parse(input.period());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(BAD_REQUEST, "Invalid billing month");
    }
    db.rows(
        s,
        "SELECT pg_advisory_xact_lock(hashtextextended(?::text||':'||?::text||':billing:'||?,0))",
        key);
    String digest = EncryptedPayloads.digest(db.json(input));
    var old =
        db.rows(
            s,
            "SELECT id,request_digest FROM partner_batch WHERE tenant_id=? AND country_code=? AND"
                + " client_key=?",
            key);
    if (!old.isEmpty()) {
      require(
          digest.equals(old.getFirst().get("request_digest")),
          "Idempotency key reused with different input");
      return get(s, (UUID) old.getFirst().get("id"));
    }
    List<List<String>> records = parseCsv(input.csv());
    require(records.size() > 1 && records.size() <= 10001, "Expected a header and 1–10000 records");
    var headers = records.getFirst();
    require(
        headers.size() >= 4
            && headers.size() <= 40
            && new HashSet<>(headers).size() == headers.size(),
        "Headers must be unique; expected 4–40 columns");
    require(
        headers.stream().allMatch(h -> h.matches("[A-Za-z][A-Za-z0-9 _-]{0,79}")),
        "Invalid column name; use short schema labels only");
    UUID id = UUID.randomUUID();
    db.update(
        s,
        "INSERT INTO"
            + " partner_batch(tenant_id,country_code,id,client_key,request_digest,partner_code,billing_period,filename,ciphertext,stage)"
            + " VALUES(?,?,?,?,?,?,?,?,?,'RECEIVED')",
        id,
        key,
        digest,
        input.partner_code(),
        input.period(),
        input.filename(),
        encryption.encrypt(input.csv(), EncryptedPayloads.context(s, id)));
    stage(s, id, "RECEIVED", null);
    stage(s, id, "PROFILING", null);
    Map<String, Object> profile =
        Map.of(
            "headers",
            headers,
            "record_count",
            records.size() - 1,
            "schema_version",
            "partner-premium-v1");
    db.update(
        s,
        "UPDATE partner_batch SET profile=v.data FROM(SELECT ?::uuid t,?::text c,?::jsonb data)v"
            + " WHERE tenant_id=v.t AND country_code=v.c AND id=?",
        db.json(profile),
        id);
    propose(s, id, headers);
    return get(s, id);
  }

  private void propose(Scope s, UUID id, List<String> headers) {
    Map proposal;
    try {
      proposal = external.mapping(headers);
    } catch (org.springframework.web.client.RestClientException e) {
      stage(s, id, "FAILED_RETRYABLE", null);
      return;
    }
    if (proposal == null) {
      stage(s, id, "FAILED_RETRYABLE", null);
      return;
    }
    Object candidate = proposal.get("mapping");
    Map<String, String> mapping = new LinkedHashMap<>();
    if (candidate instanceof Map<?, ?> m)
      for (var e : m.entrySet())
        if (e.getKey() instanceof String k
            && e.getValue() instanceof String v
            && TARGETS.contains(k)
            && headers.contains(v)) mapping.put(k, v);
    Map<String, Object> trace = new LinkedHashMap<>();
    for (String name :
        List.of(
            "model",
            "prompt_version",
            "trace_id",
            "correlation_id",
            "evaluation_status",
            "provider",
            "latency_ms",
            "cost_estimate_usd",
            "tool_trace")) if (proposal.get(name) != null) trace.put(name, proposal.get(name));
    db.update(
        s,
        "UPDATE partner_batch SET mapping=v.mapping,model_trace=v.trace FROM(SELECT ?::uuid"
            + " t,?::text c,?::jsonb mapping,?::jsonb trace)v WHERE tenant_id=v.t AND"
            + " country_code=v.c AND id=?",
        db.json(mapping),
        db.json(trace),
        id);
    stage(s, id, "MAPPING_PROPOSED", null);
    // Model confidence never substitutes for an authorized mapping decision.
    stage(s, id, "MAPPING_REVIEW", null);
  }

  @Transactional
  public Map<String, Object> resume(Scope s, UUID id) {
    var r = row(s, id);
    require(
        "FAILED_RETRYABLE".equals(r.get("stage")), "Only a retryable mapping failure can resume");
    stage(s, id, "PROFILING", null);
    propose(s, id, parseCsv(decrypt(s, r)).getFirst());
    return get(s, id);
  }

  String decrypt(Scope s, Map<String, Object> r) {
    return encryption.decrypt(
        (byte[]) r.get("ciphertext"), EncryptedPayloads.context(s, (UUID) r.get("id")));
  }

  @Transactional
  public Map<String, Object> reviewMapping(Scope s, UUID id, MappingReview input) {
    var r = row(s, id);
    require("MAPPING_REVIEW".equals(r.get("stage")), "Mapping review is not pending");
    var records = parseCsv(decrypt(s, r));
    var headers = records.getFirst();
    require(
        input.mapping().keySet().equals(TARGETS)
            && new HashSet<>(input.mapping().values()).size() == 4
            && headers.containsAll(input.mapping().values()),
        "Map each required field to a distinct existing column");
    var validation = validate(records, input.mapping(), s.country());
    db.update(
        s,
        "UPDATE partner_batch SET mapping=v.mapping,validation=v.result FROM(SELECT ?::uuid"
            + " t,?::text c,?::jsonb mapping,?::jsonb result)v WHERE tenant_id=v.t AND"
            + " country_code=v.c AND id=?",
        db.json(input.mapping()),
        db.json(validation),
        id);
    stage(s, id, "TRANSFORMED", input.evidence_reference());
    stage(s, id, "VALIDATED", input.evidence_reference());
    if (!((List<?>) validation.get("exceptions")).isEmpty())
      stage(s, id, "EXCEPTIONS_REVIEW", input.evidence_reference());
    return get(s, id);
  }

  @Transactional
  public Map<String, Object> approve(Scope s, UUID id, Approval input) {
    var r = row(s, id);
    require(
        Set.of("VALIDATED", "EXCEPTIONS_REVIEW").contains(r.get("stage")),
        "Batch is not awaiting approval");
    Map v = db.parse(r.get("validation"), Map.class);
    Set<Integer> invalid = new HashSet<>();
    for (Object value : (List<?>) v.get("exceptions"))
      invalid.add(((Number) ((Map) value).get("row")).intValue());
    require(
        input.excluded_rows().equals(invalid),
        "Explicitly exclude exactly the invalid rows; invalid records cannot be waived into"
            + " validity");
    require(((Number) v.get("valid_count")).intValue() > 0, "No valid records remain");
    db.update(
        s,
        "UPDATE partner_batch SET excluded_rows=v.data FROM(SELECT ?::uuid t,?::text c,?::jsonb"
            + " data)v WHERE tenant_id=v.t AND country_code=v.c AND id=?",
        db.json(input.excluded_rows()),
        id);
    stage(s, id, "APPROVED", input.evidence_reference());
    return get(s, id);
  }

  @Transactional
  public Map<String, Object> prepare(Scope s, UUID id, Contracts.Approval input) {
    var r = row(s, id);
    if ("COMPLETED".equals(r.get("stage"))) return get(s, id);
    require("APPROVED".equals(r.get("stage")), "Human batch approval is required");
    // Recompute from encrypted original records; no model-provided money is accepted.
    Map v = validate(parseCsv(decrypt(s, r)), db.parse(r.get("mapping"), Map.class), s.country());
    BigDecimal total = new BigDecimal(v.get("total").toString());
    require(total.signum() > 0, "A positive validated total is required");
    int added =
        db.update(
            s,
            "INSERT INTO"
                + " simulated_invoice(tenant_id,country_code,id,batch_id,partner_code,billing_period,amount,currency,record_count)"
                + " VALUES(?,?,?,?,?,?,?,?,?) ON"
                + " CONFLICT(tenant_id,country_code,partner_code,billing_period) DO NOTHING",
            UUID.randomUUID(),
            id,
            r.get("partner_code"),
            r.get("billing_period"),
            total,
            v.get("currency"),
            v.get("valid_count"));
    require(added == 1, "An invoice preparation already exists for this partner and month");
    stage(s, id, "INVOICE_PREPARED", input.evidence_reference());
    stage(s, id, "COMPLETED", input.evidence_reference());
    return get(s, id);
  }

  @Transactional
  public Map<String, Object> cancel(Scope s, UUID id, Contracts.Approval input) {
    var r = row(s, id);
    require(
        !Set.of("APPROVED", "INVOICE_PREPARED", "COMPLETED").contains(r.get("stage")),
        "An approved batch cannot be cancelled");
    if (!"CANCELLED".equals(r.get("stage"))) stage(s, id, "CANCELLED", input.evidence_reference());
    return get(s, id);
  }

  static Map<String, Object> validate(
      List<List<String>> records, Map<String, String> mapping, String country) {
    var headers = records.getFirst();
    List<Map<String, Object>> normalized = new ArrayList<>(), errors = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    BigDecimal total = BigDecimal.ZERO;
    String currency =
        Map.of("KE", "KES", "UG", "UGX", "NG", "NGN", "GH", "GHS", "ZM", "ZMW").get(country);
    for (int i = 1; i < records.size(); i++) {
      var cells = records.get(i);
      List<String> reasons = new ArrayList<>();
      Map<String, String> row = new LinkedHashMap<>();
      if (cells.size() != headers.size()) reasons.add("COLUMN_COUNT");
      for (String key : TARGETS) {
        int col = headers.indexOf(mapping.get(key));
        row.put(key, col >= 0 && col < cells.size() ? cells.get(col).trim() : "");
      }
      for (String key : List.of("customer_reference", "policy_number"))
        if (!row.get(key).matches("[A-Za-z0-9:_-]{1,100}"))
          reasons.add("INVALID_" + key.toUpperCase(Locale.ROOT));
      if (!currency.equals(row.get("currency"))) reasons.add("CURRENCY_MISMATCH");
      BigDecimal amount = BigDecimal.ZERO;
      if (!row.get("premium").matches("[0-9]{1,10}(\\.[0-9]{1,2})?"))
        reasons.add("INVALID_PREMIUM");
      else {
        amount = new BigDecimal(row.get("premium")).setScale(2);
        if (amount.signum() <= 0) reasons.add("NON_POSITIVE_PREMIUM");
      }
      if (!seen.add(row.get("policy_number"))) reasons.add("DUPLICATE_POLICY");
      if (reasons.isEmpty()) {
        total = total.add(amount);
        normalized.add(
            Map.of(
                "row",
                i + 1,
                "customer_reference",
                row.get("customer_reference"),
                "policy_number",
                row.get("policy_number"),
                "premium",
                amount.toPlainString(),
                "currency",
                currency));
      } else errors.add(Map.of("row", i + 1, "codes", reasons));
    }
    return Map.of(
        "schema_version",
        "partner-premium-v1",
        "normalized_records",
        normalized,
        "exceptions",
        errors,
        "valid_count",
        normalized.size(),
        "total",
        total.setScale(2).toPlainString(),
        "currency",
        currency,
        "record_count",
        records.size() - 1);
  }

  static List<List<String>> parseCsv(String text) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false, closed = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else if (quoted) {
          quoted = false;
          closed = true;
        } else {
          require(cell.isEmpty() && !closed, "Malformed CSV quoting");
          quoted = true;
        }
      } else if (quoted) cell.append(c);
      else if (c == ',' || c == '\n' || c == '\r') {
        row.add(cell.toString());
        cell.setLength(0);
        closed = false;
        if (c != ',') {
          if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
          rows.add(row);
          row = new ArrayList<>();
        }
      } else {
        require(!closed, "Unexpected characters after a quoted field");
        cell.append(c);
      }
      require(
          cell.length() <= 2000 && row.size() <= 40 && rows.size() <= 10001,
          "CSV exceeds processing limits");
    }
    require(!quoted, "Unclosed quoted field");
    if (!cell.isEmpty() || !row.isEmpty() || closed) {
      row.add(cell.toString());
      rows.add(row);
    }
    return rows;
  }
}
