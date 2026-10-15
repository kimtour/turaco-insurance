package com.sam.insurance;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsService {
  private final Store db;

  public OperationsService(Store db) {
    this.db = db;
  }

  @Transactional
  public Map drain(Scope s, boolean simulateFailure) {
    var pending =
        db.rows(
            s,
            "SELECT id FROM outbox_event WHERE tenant_id=? AND country_code=? AND published_at IS"
                + " NULL ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED");
    for (Map event : pending) {
      UUID id = (UUID) event.get("id");
      db.update(
          s,
          "INSERT INTO queue_delivery(tenant_id,country_code,id,event_id) VALUES(?,?,?,?) ON"
              + " CONFLICT(event_id) DO NOTHING",
          UUID.randomUUID(),
          id);
      db.update(
          s,
          "UPDATE outbox_event SET published_at=now() WHERE tenant_id=? AND country_code=? AND"
              + " id=?",
          id);
    }
    int delivered = 0, dead = 0;
    for (Map delivery :
        db.rows(
            s,
            "SELECT * FROM queue_delivery WHERE tenant_id=? AND country_code=? AND status='READY'"
                + " AND available_at<=now() ORDER BY created_at LIMIT 100 FOR UPDATE SKIP"
                + " LOCKED")) {
      UUID id = (UUID) delivery.get("id");
      int attempts = ((Number) delivery.get("attempts")).intValue() + 1;
      String status = simulateFailure ? (attempts >= 3 ? "DEAD" : "READY") : "DONE";
      db.update(
          s,
          "UPDATE queue_delivery SET attempts=v.attempts,status=v.state,available_at=now()+interval"
              + " '1 second'*power(2,v.attempts) FROM(SELECT ?::uuid t,?::text c,?::int"
              + " attempts,?::text state)v WHERE tenant_id=v.t AND country_code=v.c AND id=?",
          attempts,
          status,
          id);
      if ("DONE".equals(status)) {
        db.update(
            s,
            "INSERT INTO processed_event(tenant_id,country_code,event_id) VALUES(?,?,?) ON CONFLICT"
                + " DO NOTHING",
            delivery.get("event_id"));
        delivered++;
      }
      if ("DEAD".equals(status)) dead++;
    }
    return Map.of(
        "published",
        pending.size(),
        "delivered",
        delivered,
        "dead_lettered",
        dead,
        "queue",
        "LOCAL_POSTGRES_SIMULATOR");
  }

  public Map metrics(Scope s) {
    return Map.of(
        "outbox_pending",
        count(
            s,
            "SELECT count(*) total FROM outbox_event WHERE tenant_id=? AND country_code=? AND"
                + " published_at IS NULL"),
        "queue_ready",
        count(
            s,
            "SELECT count(*) total FROM queue_delivery WHERE tenant_id=? AND country_code=? AND"
                + " status='READY'"),
        "dead_letters",
        count(
            s,
            "SELECT count(*) total FROM queue_delivery WHERE tenant_id=? AND country_code=? AND"
                + " status='DEAD'"),
        "unknown_payouts",
        count(
            s,
            "SELECT count(*) total FROM claim WHERE tenant_id=? AND country_code=? AND"
                + " payout_status='UNKNOWN'"),
        "provider_sync_unknown",
        count(
            s,
            "SELECT count(*) total FROM claim WHERE tenant_id=? AND country_code=? AND"
                + " provider_claim_status='SYNC_UNKNOWN'"),
        "pending_channel_messages",
        count(
            s,
            "SELECT count(*) total FROM channel_receipt WHERE tenant_id=? AND country_code=? AND"
                + " status='PENDING'"));
  }

  private long count(Scope s, String sql) {
    return ((Number) db.one(s, sql).get("total")).longValue();
  }
}
