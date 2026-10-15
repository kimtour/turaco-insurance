package com.sam.insurance;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.queue-worker-enabled", havingValue = "true")
public class QueueWorker {
  private final JdbcTemplate jdbc;
  private final OperationsService operations;
  private final PlatformService platform;
  private final Store db;

  public QueueWorker(
      JdbcTemplate jdbc, OperationsService operations, PlatformService platform, Store db) {
    this.jdbc = jdbc;
    this.operations = operations;
    this.platform = platform;
    this.db = db;
  }

  @Scheduled(fixedDelayString = "${app.queue-worker-delay-ms:10000}")
  public void tick() {
    for (Map row : jdbc.queryForList("SELECT tenant_id,country_code FROM country_config")) {
      Scope s =
          new Scope(
              (UUID) row.get("tenant_id"),
              row.get("country_code").toString(),
              "local-event-worker");
      try {
        operations.drain(s, false);
        for (Map pending :
            db.rows(
                s,
                "SELECT id FROM claim WHERE tenant_id=? AND country_code=? AND"
                    + " provider_kind='LOCAL_SIMULATOR' AND provider_claim_status IN"
                    + " ('RECEIVED','PROCESSING','SUBMISSION_PENDING','SYNC_UNKNOWN') ORDER BY"
                    + " created_at LIMIT 20")) {
          try {
            platform.reconcileProvider(s, (UUID) pending.get("id"));
          } catch (RuntimeException ex) {
            /* Retained as a synchronization exception; retry on the next bounded cycle. */
          }
        }
      } catch (RuntimeException ex) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Local event worker cycle failed: {}", ex.getClass().getSimpleName());
      }
    }
  }
}
