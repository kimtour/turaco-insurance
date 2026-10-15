package com.sam.insurance;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class Store {
  private final JdbcTemplate jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  public Store(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private Object[] args(Scope s, Object... tail) {
    Object[] args = new Object[tail.length + 2];
    args[0] = s.tenant();
    args[1] = s.country();
    System.arraycopy(tail, 0, args, 2, tail.length);
    return args;
  }

  public Map<String, Object> one(Scope s, String sql, Object... tail) {
    return jdbc.queryForMap(sql, args(s, tail));
  }

  public List<Map<String, Object>> rows(Scope s, String sql, Object... tail) {
    return jdbc.queryForList(sql, args(s, tail));
  }

  public int update(Scope s, String sql, Object... tail) {
    return jdbc.update(sql, args(s, tail));
  }

  public String json(Object value) {
    return json.writeValueAsString(value);
  }

  public <T> T parse(Object value, Class<T> type) {
    return json.readValue(value.toString(), type);
  }

  public void audit(
      Scope s, UUID aggregate, String type, Map<String, ?> details, Integer policyVersion) {
    UUID event = UUID.randomUUID();
    update(
        s,
        "INSERT INTO"
            + " audit_event(tenant_id,country_code,id,aggregate_id,actor,event_type,detail,correlation_id,source,policy_version)"
            + " VALUES(?,?,?,?,?,?,?::jsonb,?,'standalone',?)",
        event,
        aggregate,
        s.actor(),
        type,
        json(details),
        CorrelationFilter.current(),
        policyVersion);
    update(
        s,
        "INSERT INTO outbox_event(tenant_id,country_code,id,aggregate_id,event_type,payload)"
            + " VALUES(?,?,?,?,?,?::jsonb)",
        event,
        aggregate,
        type,
        json(Map.of("event_id", event, "aggregate_id", aggregate, "event_type", type)));
  }
}
