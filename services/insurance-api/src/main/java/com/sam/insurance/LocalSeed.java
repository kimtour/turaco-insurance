package com.sam.insurance;

import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
@ConditionalOnProperty(name = "app.seed", havingValue = "true")
public class LocalSeed implements CommandLineRunner {
  public static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  public static final UUID CUSTOMER = UUID.fromString("22222222-2222-2222-2222-222222222222");
  public static final UUID PRODUCT = UUID.fromString("33333333-3333-3333-3333-333333333333");
  public static final UUID POLICY = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private final JdbcTemplate jdbc;

  public LocalSeed(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void run(String... args) {
    jdbc.update(
        "INSERT INTO tenant(id,name) VALUES(?,'Synthetic local demo') ON CONFLICT DO NOTHING",
        TENANT);
    jdbc.update(
        "INSERT INTO country_config VALUES(?,'KE','LOCAL_SYNTHETIC_ONLY',30) ON CONFLICT DO"
            + " NOTHING",
        TENANT);
    jdbc.update(
        "INSERT INTO customer(id,tenant_id,country_code,full_name,mobile_number)"
            + " VALUES(?,?,'KE','Synthetic Customer','+254700000000') ON CONFLICT DO NOTHING",
        CUSTOMER,
        TENANT);
    jdbc.update(
        "INSERT INTO product(id,tenant_id,country_code,code,name)"
            + " VALUES(?,?,'KE','HOSPITAL_CASH','Hospital Cash (synthetic terms)') ON CONFLICT DO"
            + " NOTHING",
        PRODUCT,
        TENANT);
    jdbc.update(
        "INSERT INTO"
            + " coverage(id,tenant_id,country_code,product_id,benefit_code,incident_type_code,daily_amount,currency,max_nights,waiting_days,rule_version)"
            + " VALUES(?,?,'KE',?,'HC_DAILY','HOSPITALIZATION',1500,'KES',30,30,'synthetic-hc-v1')"
            + " ON CONFLICT DO NOTHING",
        UUID.fromString("55555555-5555-5555-5555-555555555555"),
        TENANT,
        PRODUCT);
    jdbc.update(
        "INSERT INTO"
            + " policy(id,tenant_id,country_code,customer_id,product_id,policy_number,start_date,end_date,premium,currency,status)"
            + " VALUES(?,?,'KE',?,?,'SYN-KE-001','2026-01-01','2026-12-31',500,'KES','ACTIVE') ON"
            + " CONFLICT DO NOTHING",
        POLICY,
        TENANT,
        CUSTOMER,
        PRODUCT);
  }
}
