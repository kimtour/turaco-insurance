package com.sam.insurance;

import static com.sam.insurance.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "app.seed=true",
      "app.payload-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "app.whatsapp-secret=synthetic-signature-secret",
      "app.whatsapp-verify-token=synthetic-verify-token",
      "app.provider-callback-token=synthetic-callback-token"
    })
@ActiveProfiles("local")
@AutoConfigureMockMvc
class ClaimsIntegrationTest {
  static PostgreSQLContainer postgres;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    String external = System.getenv("TEST_DATABASE_URL");
    if (external == null) {
      postgres = new PostgreSQLContainer("postgres:17.11-alpine");
      postgres.start();
      r.add("spring.datasource.url", postgres::getJdbcUrl);
      r.add("spring.datasource.username", postgres::getUsername);
      r.add("spring.datasource.password", postgres::getPassword);
    } else {
      r.add("spring.datasource.url", () -> external);
      r.add(
          "spring.datasource.username",
          () -> System.getenv().getOrDefault("TEST_DATABASE_USER", "turaco_test"));
      r.add(
          "spring.datasource.password",
          () -> System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "unused"));
    }
  }

  @AfterAll
  static void stop() {
    if (postgres != null) postgres.stop();
  }

  @Autowired ClaimsService service;
  @Autowired Store db;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired PlatformService platform;
  @Autowired ChannelService channel;
  @Autowired OperationsService operations;
  @Autowired RiskService risk;
  @Autowired BillingService billing;
  @Autowired CompletenessService quality;
  @Autowired ChannelSimulationService channels;
  @org.springframework.test.context.bean.override.mockito.MockitoBean ExternalServices external;
  final Scope scope = new Scope(LocalSeed.TENANT, "KE", "synthetic-test-human");

  <T> Extracted<T> field(T value) {
    return new Extracted<>(
        value, FieldStatus.extracted, .9, "synthetic quoted evidence", "message");
  }

  Extraction sample() {
    return new Extraction(
        "2",
        "en-KE",
        field("HOSPITALIZATION"),
        field("Synthetic Hospital"),
        field(LocalDate.of(2026, 9, 1)),
        field(LocalDate.of(2026, 9, 4)),
        field(9999),
        true);
  }

  UUID intake() {
    return service
        .create(scope, UUID.randomUUID().toString(), new CreateClaim(LocalSeed.POLICY, sample()))
        .id();
  }

  UUID ready() {
    UUID id = intake();
    service.confirm(scope, id, new Confirmation(ClaimsService.CRITICAL, "synthetic-confirmation"));
    service.verify(scope, id, new Verification("synthetic-assisted-identity", true));
    service.submit(scope, id);
    service.review(
        scope, id, new Review("RECOMMEND_APPROVAL", "SYNTHETIC_TERMS_MET", "evidence:synthetic"));
    return id;
  }

  @Test
  void restCreationIsIdempotentAndRequiresExplicitConfirmation() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = db.json(new CreateClaim(LocalSeed.POLICY, sample()));
    var auth =
        jwt()
            .jwt(
                j ->
                    j.subject(scope.actor())
                        .claim("tenant_id", scope.tenant().toString())
                        .claim("country_code", "KE"))
            .authorities(new SimpleGrantedAuthority("claims:write"));
    String response =
        mvc.perform(
                post("/api/v1/claims")
                    .with(auth)
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(db.parse(response, Map.class).get("id").toString());
    mvc.perform(
            post("/api/v1/claims")
                .with(auth)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(id.toString()));
    mvc.perform(post("/api/v1/claims/" + id + "/submissions").with(auth))
        .andExpect(status().isConflict());
  }

  @Test
  void aReviewerCannotApprove() throws Exception {
    UUID id = ready();
    mvc.perform(
            post("/api/v1/claims/" + id + "/approvals")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.claim("tenant_id", scope.tenant().toString())
                                    .claim("country_code", "KE"))
                        .authorities(new SimpleGrantedAuthority("claims:review")))
                .contentType("application/json")
                .content("{\"evidence_reference\":\"synthetic\"}"))
        .andExpect(status().isForbidden());
    assertEquals("NOT_AUTHORIZED", service.get(scope, id).payout_status());
  }

  @Test
  void duplicateAndConcurrentApprovalsAndPaymentsAreSafe() throws Exception {
    UUID id = ready();
    List<Future<ClaimView>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(() -> service.approve(scope, id, new Approval("evidence:synthetic"))));
      Set<UUID> obligations = new HashSet<>();
      for (Future<ClaimView> result : results)
        obligations.add(result.get(20, TimeUnit.SECONDS).obligation_id());
      assertEquals(1, obligations.size());
      results.clear();
      for (int i = 0; i < 8; i++)
        results.add(pool.submit(() -> service.simulate(scope, id, new Payment("CONFIRMED"))));
      for (Future<ClaimView> result : results)
        assertEquals("CONFIRMED", result.get(20, TimeUnit.SECONDS).payout_status());
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM payment_obligation WHERE claim_id=?", Integer.class, id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM payout_attempt a JOIN payment_obligation o ON"
                + " o.id=a.obligation_id WHERE o.claim_id=?",
            Integer.class,
            id));
    assertEquals(
        new BigDecimal("4500.00"),
        service.get(scope, id).amount()); // model's 9999 nights is ignored
  }

  @Test
  void concurrentCreationReturnsOneClaimAndChangedPayloadIsRejected() throws Exception {
    String key = UUID.randomUUID().toString();
    CreateClaim request = new CreateClaim(LocalSeed.POLICY, sample());
    Set<UUID> ids = new HashSet<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
      List<Future<ClaimView>> results = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        results.add(pool.submit(() -> service.create(scope, key, request)));
      for (var result : results) ids.add(result.get(20, TimeUnit.SECONDS).id());
    }
    assertEquals(1, ids.size());
    Extraction e = sample();
    Extraction different =
        new Extraction(
            "2",
            "sw-KE",
            e.incident_type_code(),
            e.hospital_name(),
            e.admission_date(),
            e.discharge_date(),
            e.hospital_nights(),
            true);
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.create(scope, key, new CreateClaim(LocalSeed.POLICY, different)));
  }

  @Test
  void unknownPayoutCannotBeBlindlyRetried() {
    UUID id = ready();
    service.approve(scope, id, new Approval("synthetic"));
    service.simulate(scope, id, new Payment("UNKNOWN"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.simulate(scope, id, new Payment("CONFIRMED")));
    assertEquals("UNKNOWN", service.get(scope, id).payout_status());
  }

  @Test
  void catalogRejectsUnknownIncidentAndMissingCriticalValues() {
    Extraction e = sample();
    Extraction invalid =
        new Extraction(
            "2",
            "en-KE",
            field("INVENTED"),
            e.hospital_name(),
            e.admission_date(),
            e.discharge_date(),
            e.hospital_nights(),
            true);
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.create(scope, "unknown-code", new CreateClaim(LocalSeed.POLICY, invalid)));
    Extracted<LocalDate> missing = new Extracted<>(null, FieldStatus.missing, null, null, null);
    UUID id =
        service
            .create(
                scope,
                UUID.randomUUID().toString(),
                new CreateClaim(
                    LocalSeed.POLICY,
                    new Extraction(
                        "2",
                        "en-KE",
                        e.incident_type_code(),
                        e.hospital_name(),
                        e.admission_date(),
                        missing,
                        e.hospital_nights(),
                        true)))
            .id();
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.confirm(scope, id, new Confirmation(ClaimsService.CRITICAL, "synthetic")));
  }

  @Test
  void tenantAndCountryBoundariesAreEnforced() throws Exception {
    UUID id = intake();
    for (String[] pair :
        List.of(
            new String[] {UUID.randomUUID().toString(), "KE"},
            new String[] {scope.tenant().toString(), "UG"})) {
      mvc.perform(
              get("/api/v1/claims/" + id)
                  .with(
                      jwt()
                          .jwt(j -> j.claim("tenant_id", pair[0]).claim("country_code", pair[1]))
                          .authorities(new SimpleGrantedAuthority("claims:read"))))
          .andExpect(status().isNotFound());
    }
    mvc.perform(
            get("/api/v1/claims/" + id)
                .with(jwt().authorities(new SimpleGrantedAuthority("claims:read"))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/claims/" + id)).andExpect(status().isUnauthorized());
  }

  @Test
  void historyIsImmutableAndFinancialUniquenessIsDatabaseEnforced() {
    UUID id = ready();
    service.approve(scope, id, new Approval("evidence:synthetic"));
    List<Map<String, Object>> audit = service.audit(scope, id);
    assertTrue(
        audit.stream()
            .anyMatch(
                r ->
                    "REVIEW_RECORDED".equals(r.get("event_type"))
                        && r.get("actor") != null
                        && r.get("created_at") != null
                        && r.get("detail").toString().contains("evidence:synthetic")));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("DELETE FROM audit_event WHERE aggregate_id=?", id));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO payment_obligation SELECT"
                    + " ?,tenant_id,country_code,claim_id,benefit_code,payout_version,amount,currency,status,?,rule_version,policy_version,created_at"
                    + " FROM payment_obligation WHERE claim_id=?",
                UUID.randomUUID(),
                "duplicate-test:" + UUID.randomUUID(),
                id));
  }

  @Test
  void expiredCoverageAndUnverifiedPayeeBlockApproval() {
    UUID id = intake();
    service.confirm(scope, id, new Confirmation(ClaimsService.CRITICAL, "synthetic"));
    service.verify(scope, id, new Verification("synthetic", false));
    service.submit(scope, id);
    service.review(scope, id, new Review("RECOMMEND_APPROVAL", "SYNTHETIC", "evidence:synthetic"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.approve(scope, id, new Approval("synthetic")));
    Extraction e = sample();
    Extraction waiting =
        new Extraction(
            "2",
            "en-KE",
            e.incident_type_code(),
            e.hospital_name(),
            field(LocalDate.of(2026, 1, 2)),
            field(LocalDate.of(2026, 1, 4)),
            e.hospital_nights(),
            true);
    UUID waitingId =
        service
            .create(scope, UUID.randomUUID().toString(), new CreateClaim(LocalSeed.POLICY, waiting))
            .id();
    service.confirm(scope, waitingId, new Confirmation(ClaimsService.CRITICAL, "synthetic"));
    service.verify(scope, waitingId, new Verification("synthetic", true));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.submit(scope, waitingId));
  }

  @Test
  void integrationModeFailsClosed() {
    assertThrows(IllegalStateException.class, () -> new ModeGuard("integration"));
  }

  @Test
  void riskFlagsOnlyRouteToHumanReviewAndNeverDecideOrReserveMoney() {
    UUID id = intake();
    Map result = risk.assess(scope, id);
    assertEquals("HUMAN_REVIEW", result.get("route"));
    assertEquals(false, result.get("automatic_decision"));
    assertTrue(((List) result.get("flags")).contains("CRITICAL_FIELDS_REQUIRE_CONFIRMATION"));
    assertEquals("NOT_SUBMITTED", service.get(scope, id).provider_claim_status());
    assertNull(service.get(scope, id).obligation_id());
  }

  @Test
  void signedChannelReceiptsDeduplicateAndCreateOneUnconfirmedIntake() throws Exception {
    String message = "wamid:" + UUID.randomUUID();
    String raw =
        db.json(
            Map.of(
                "entry",
                List.of(
                    Map.of(
                        "changes",
                        List.of(
                            Map.of(
                                "value",
                                Map.of(
                                    "metadata",
                                    Map.of("phone_number_id", "SYNTHETIC_NUMBER"),
                                    "messages",
                                    List.of(
                                        Map.of(
                                            "id",
                                            message,
                                            "type",
                                            "text",
                                            "text",
                                            Map.of("body", "Synthetic hospitalization"))))))))));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> channel.receive(raw, "sha256=invalid"));
    javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(
        new javax.crypto.spec.SecretKeySpec(
            "synthetic-signature-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "HmacSHA256"));
    String signature =
        "sha256="
            + HexFormat.of()
                .formatHex(mac.doFinal(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(1, channel.receive(raw, signature).get("accepted"));
    assertEquals(1, channel.receive(raw, signature).get("duplicates"));
    UUID receipt =
        (UUID)
            db.one(
                    scope,
                    "SELECT id FROM channel_receipt WHERE tenant_id=? AND country_code=? AND"
                        + " message_id=?",
                    message)
                .get("id");
    org.mockito.Mockito.when(external.extract("Synthetic hospitalization", "en-KE"))
        .thenReturn(sample());
    var claim = channel.process(scope, receipt, LocalSeed.POLICY, "en-KE", null);
    assertEquals(claim.id(), channel.process(scope, receipt, LocalSeed.POLICY, "en-KE", null).id());
    assertEquals("NEEDS_CONFIRMATION", claim.intake_status());
    org.mockito.Mockito.verify(external, org.mockito.Mockito.times(1))
        .extract("Synthetic hospitalization", "en-KE");
    assertEquals(
        "synthetic-challenge",
        channel.challenge("subscribe", "synthetic-verify-token", "synthetic-challenge"));
  }

  @Test
  void encryptedEvidenceConflictsBlockMoneyUntilMatchingReplacementIsReviewed() {
    UUID id = ready();
    org.mockito.Mockito.when(
            external.document(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of("status", "CONFLICT", "engine", "synthetic-text-parser"));
    Map bad =
        platform.evidence(
            scope, id, new PlatformContracts.Evidence("Synthetic private evidence conflict"));
    UUID evidence = (UUID) bad.get("id");
    byte[] body =
        (byte[])
            db.one(
                    scope,
                    "SELECT ciphertext FROM claim_evidence WHERE tenant_id=? AND country_code=? AND"
                        + " id=?",
                    evidence)
                .get("ciphertext");
    assertFalse(
        new String(body, java.nio.charset.StandardCharsets.UTF_8)
            .contains("Synthetic private evidence"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.approve(scope, id, new Approval("synthetic")));
    org.mockito.Mockito.when(
            external.document(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of("status", "MATCH", "engine", "synthetic-text-parser"));
    Map good =
        platform.evidence(
            scope, id, new PlatformContracts.Evidence("Synthetic replacement evidence"));
    platform.resolveEvidence(
        scope,
        id,
        evidence,
        new PlatformContracts.Resolution((UUID) good.get("id"), "synthetic-human-evidence-review"));
    assertNotNull(service.approve(scope, id, new Approval("synthetic-reviewed")).obligation_id());
  }

  @Test
  void providerSnapshotsOwnStatusAndOldCallbacksCannotOverwriteLatestApproval() {
    UUID id = intake();
    service.confirm(scope, id, new Confirmation(ClaimsService.CRITICAL, "synthetic"));
    service.verify(scope, id, new Verification("synthetic", true));
    Map received =
        Map.of(
            "id",
            UUID.randomUUID().toString(),
            "internal_claim_id",
            id.toString(),
            "tenant_id",
            scope.tenant().toString(),
            "country_code",
            "KE",
            "provider",
            "LOCAL_SIMULATOR",
            "status",
            "RECEIVED",
            "revision",
            1);
    org.mockito.Mockito.when(external.submit(scope, id, LocalSeed.POLICY)).thenReturn(received);
    platform.providerSubmit(scope, id);
    service.review(scope, id, new Review("RECOMMEND_APPROVAL", "SYNTHETIC", "synthetic"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.approve(scope, id, new Approval("synthetic")));
    Map approved = new HashMap(received);
    approved.put("revision", 2);
    approved.put("status", "APPROVED");
    org.mockito.Mockito.when(
            external.snapshot(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(id)))
        .thenReturn(approved);
    var event = new PlatformContracts.ProviderEvent(scope.tenant(), "KE", id, UUID.randomUUID(), 1);
    platform.providerEvent(event);
    platform.providerEvent(event);
    assertEquals("APPROVED", service.get(scope, id).provider_claim_status());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM provider_receipt WHERE claim_id=?", Integer.class, id));
    assertNotNull(
        service.approve(scope, id, new Approval("synthetic-human-reservation")).obligation_id());
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> platform.reject(scope, id, new Approval("synthetic")));
  }

  @Test
  void unknownPayoutReconcilesWithoutCreatingAnotherAttempt() {
    UUID id = ready();
    service.approve(scope, id, new Approval("synthetic"));
    service.simulate(scope, id, new Payment("UNKNOWN"));
    platform.paymentReceipt(
        scope, id, new PlatformContracts.Receipt("CONFIRMED", "synthetic-provider-receipt"));
    assertEquals("CONFIRMED", platform.reconcilePayment(scope, id).payout_status());
    assertEquals("CONFIRMED", platform.reconcilePayment(scope, id).payout_status());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM payout_attempt a JOIN payment_obligation o ON"
                + " a.obligation_id=o.id WHERE o.claim_id=?",
            Integer.class,
            id));
  }

  @Test
  void informationRequestsSupportCorrectionsAndReconfirmationWithoutDuplicateSubmission() {
    UUID id = ready();
    service.review(
        scope, id, new Review("REQUEST_INFORMATION", "SYNTHETIC_CORRECTION", "synthetic"));
    assertEquals("NEEDS_CONFIRMATION", service.get(scope, id).intake_status());
    platform.correct(scope, id, new PlatformContracts.Correction(sample(), "synthetic-correction"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.submit(scope, id));
    service.confirm(
        scope, id, new Confirmation(ClaimsService.CRITICAL, "synthetic-reconfirmation"));
    assertEquals("RECEIVED", service.submit(scope, id).provider_claim_status());
    service.review(
        scope, id, new Review("RECOMMEND_REJECTION", "SYNTHETIC_TERMS_NOT_MET", "synthetic"));
    assertEquals(
        "REJECTED",
        platform
            .reject(scope, id, new Approval("synthetic-human-rejection"))
            .provider_claim_status());
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.approve(scope, id, new Approval("synthetic")));
  }

  @Test
  void localQueueRetriesReachDeadLettersAndNeverProcessAnEventTwice() {
    Scope queueScope = new Scope(UUID.randomUUID(), "KE", "synthetic-queue-operator");
    jdbc.update("INSERT INTO tenant(id,name) VALUES(?,'Isolated queue test')", queueScope.tenant());
    jdbc.update(
        "INSERT INTO country_config VALUES(?,'KE','LOCAL_SYNTHETIC_ONLY',30)", queueScope.tenant());
    db.audit(queueScope, UUID.randomUUID(), "SYNTHETIC_QUEUE_TEST", Map.of(), null);
    operations.drain(queueScope, true);
    jdbc.update(
        "UPDATE queue_delivery SET available_at=now()-interval '1 second' WHERE tenant_id=?",
        queueScope.tenant());
    operations.drain(queueScope, true);
    jdbc.update(
        "UPDATE queue_delivery SET available_at=now()-interval '1 second' WHERE tenant_id=?",
        queueScope.tenant());
    assertEquals(1, operations.drain(queueScope, true).get("dead_lettered"));
    db.audit(queueScope, UUID.randomUUID(), "SYNTHETIC_QUEUE_TEST", Map.of(), null);
    assertEquals(1, operations.drain(queueScope, false).get("delivered"));
    assertEquals(0, operations.drain(queueScope, false).get("delivered"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM processed_event WHERE tenant_id=?",
            Integer.class,
            queueScope.tenant()));
  }

  @Test
  void concurrentTextRetriesReuseTheClaimWithoutRepeatingExtraction() throws Exception {
    String key = UUID.randomUUID().toString();
    var input = new PlatformContracts.TextIntake(LocalSeed.POLICY, "Synthetic retry", "en-KE");
    org.mockito.Mockito.when(external.extract(input.text(), input.language())).thenReturn(sample());
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      var work = new ArrayList<Callable<ClaimView>>();
      for (int i = 0; i < 4; i++) work.add(() -> platform.fromText(scope, key, input));
      Set<UUID> ids = new HashSet<>();
      for (var result : pool.invokeAll(work)) ids.add(result.get().id());
      assertEquals(1, ids.size());
      org.mockito.Mockito.when(external.extract(input.text(), input.language()))
          .thenThrow(new org.springframework.web.client.RestClientException("synthetic outage"));
      assertEquals(ids.iterator().next(), platform.fromText(scope, key, input).id());
      org.mockito.Mockito.verify(external, org.mockito.Mockito.times(1))
          .extract(input.text(), input.language());
      assertThrows(
          org.springframework.web.server.ResponseStatusException.class,
          () ->
              platform.fromText(
                  scope,
                  key,
                  new PlatformContracts.TextIntake(LocalSeed.POLICY, "Changed request", "en-KE")));
      assertThrows(
          org.springframework.web.server.ResponseStatusException.class,
          () -> platform.fromText(scope, "invalid key", input));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void requestedInformationMustBeResubmittedBeforeApproval() {
    UUID id = ready();
    service.review(scope, id, new Review("REQUEST_INFORMATION", "SYNTHETIC_RECHECK", "synthetic"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            service.review(
                scope, id, new Review("RECOMMEND_APPROVAL", "SYNTHETIC_RECHECK", "synthetic")));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.approve(scope, id, new Approval("synthetic")));
    assertNull(service.get(scope, id).obligation_id());
    service.confirm(
        scope, id, new Confirmation(ClaimsService.CRITICAL, "synthetic-reconfirmation"));
    service.submit(scope, id);
    service.review(scope, id, new Review("RECOMMEND_APPROVAL", "SYNTHETIC_RECHECK", "synthetic"));
    var approved = service.approve(scope, id, new Approval("synthetic"));
    assertNotNull(approved.obligation_id());
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            service.review(
                scope, id, new Review("REQUEST_INFORMATION", "SYNTHETIC_RECHECK", "synthetic")));
    assertEquals(approved.obligation_id(), service.get(scope, id).obligation_id());
  }

  @Test
  void correctedFactsRequireCurrentEvidenceWithoutRewritingEarlierResolutions() {
    UUID id = ready();
    org.mockito.Mockito.when(
            external.document(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of("status", "CONFLICT", "engine", "synthetic-text-parser"));
    UUID bad =
        (UUID)
            platform
                .evidence(scope, id, new PlatformContracts.Evidence("Synthetic conflict"))
                .get("id");
    org.mockito.Mockito.when(
            external.document(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of("status", "MATCH", "engine", "synthetic-text-parser"));
    UUID oldMatch =
        (UUID)
            platform
                .evidence(scope, id, new PlatformContracts.Evidence("Synthetic old match"))
                .get("id");
    platform.resolveEvidence(
        scope, id, bad, new PlatformContracts.Resolution(oldMatch, "synthetic-first-review"));
    var initialEvidence = platform.evidenceList(scope, id);
    assertTrue(
        initialEvidence.stream()
            .anyMatch(
                row -> bad.equals(row.get("id")) && Boolean.TRUE.equals(row.get("resolved"))));
    service.review(
        scope, id, new Review("REQUEST_INFORMATION", "SYNTHETIC_CORRECTION", "synthetic"));
    Extraction old = sample();
    Extraction corrected =
        new Extraction(
            "2",
            old.language(),
            old.incident_type_code(),
            field("Corrected Synthetic Hospital"),
            old.admission_date(),
            old.discharge_date(),
            old.hospital_nights(),
            true);
    platform.correct(
        scope, id, new PlatformContracts.Correction(corrected, "synthetic-correction"));
    for (var row : platform.evidenceList(scope, id)) {
      assertEquals(false, row.get("facts_current"));
      assertEquals(false, row.get("resolved"));
      assertFalse(row.containsKey("extraction_digest"));
    }
    service.confirm(
        scope, id, new Confirmation(ClaimsService.CRITICAL, "synthetic-reconfirmation"));
    service.submit(scope, id);
    service.review(scope, id, new Review("RECOMMEND_APPROVAL", "SYNTHETIC_RECHECK", "synthetic"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.approve(scope, id, new Approval("synthetic")));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            platform.resolveEvidence(
                scope, id, bad, new PlatformContracts.Resolution(oldMatch, "synthetic-stale")));
    UUID current =
        (UUID)
            platform
                .evidence(scope, id, new PlatformContracts.Evidence("Synthetic current match"))
                .get("id");
    platform.resolveEvidence(
        scope, id, bad, new PlatformContracts.Resolution(current, "synthetic-new-review"));
    platform.resolveEvidence(
        scope, id, oldMatch, new PlatformContracts.Resolution(current, "synthetic-new-review"));
    var reviewedEvidence = platform.evidenceList(scope, id);
    assertTrue(
        reviewedEvidence.stream()
            .filter(row -> !current.equals(row.get("id")))
            .allMatch(row -> Boolean.TRUE.equals(row.get("resolved"))));
    assertTrue(
        reviewedEvidence.stream()
            .anyMatch(
                row ->
                    current.equals(row.get("id"))
                        && Boolean.TRUE.equals(row.get("facts_current"))));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM evidence_resolution WHERE evidence_id=?", Integer.class, bad));
    assertNotNull(service.approve(scope, id, new Approval("synthetic-current")).obligation_id());
  }

  @Test
  void notificationWindowsOptOutAndInvalidPaginationAreEnforced() throws Exception {
    UUID id = intake();
    assertEquals(
        "LOCAL_TEMPLATE_SIMULATOR",
        channel
            .notification(
                scope,
                new PlatformContracts.Notification(
                    id,
                    "sw-KE",
                    "claim_received",
                    java.time.Instant.now().minusSeconds(90000),
                    false))
            .get("mode"));
    assertEquals(
        "SUPPRESSED",
        channel
            .notification(
                scope,
                new PlatformContracts.Notification(
                    id, "en-KE", "claim_received", java.time.Instant.now(), true))
            .get("status"));
    mvc.perform(
            get("/api/v1/claims?page=-1")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(scope.actor())
                                    .claim("tenant_id", scope.tenant().toString())
                                    .claim("country_code", "KE"))
                        .authorities(new SimpleGrantedAuthority("claims:read"))))
        .andExpect(status().isBadRequest());
  }

  final Map<String, String> normalMapping =
      Map.of(
          "customer_reference",
          "customer_reference",
          "policy_number",
          "policy_number",
          "premium",
          "premium",
          "currency",
          "currency");

  Map mappingProposal(Map<String, String> mapping) {
    return Map.of(
        "mapping",
        mapping,
        "provider",
        "mock",
        "model",
        "synthetic-mapping-v1",
        "prompt_version",
        "partner-mapping-v1",
        "trace_id",
        UUID.randomUUID().toString(),
        "tool_trace",
        List.of("inspect_schema", "lookup_aliases"),
        "evaluation_status",
        "synthetic_regression");
  }

  Map<String, Object> batch(String partner, String filename) throws Exception {
    String csv =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("../../fixtures/partner-billing/" + filename));
    org.mockito.Mockito.when(external.mapping(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(mappingProposal(normalMapping));
    return billing.upload(
        scope,
        UUID.randomUUID().toString(),
        new BillingService.Upload(partner, "2026-10", filename, csv));
  }

  @Test
  void billingFixturesNormalizeAndConcurrentInvoicePreparationIsUnique() throws Exception {
    Map expected =
        db.parse(
            java.nio.file.Files.readString(
                java.nio.file.Path.of("../../fixtures/partner-billing/expected.json")),
            Map.class);
    for (String filename : List.of("normal.csv", "renamed.csv")) {
      var created = batch("partner_" + UUID.randomUUID(), filename);
      UUID id = (UUID) created.get("id");
      assertEquals("MAPPING_REVIEW", created.get("stage"));
      Map<String, String> mapping =
          filename.equals("normal.csv")
              ? normalMapping
              : Map.of(
                  "customer_reference",
                  "member_id",
                  "policy_number",
                  "policy_no",
                  "premium",
                  "premium_amount",
                  "currency",
                  "ccy");
      var reviewed =
          billing.reviewMapping(
              scope, id, new BillingService.MappingReview(mapping, "synthetic-map-review"));
      Map validation = (Map) reviewed.get("validation");
      assertEquals(expected.get("total"), validation.get("total"));
      assertEquals(expected.get("normalized_records"), validation.get("normalized_records"));
      assertThrows(
          org.springframework.web.server.ResponseStatusException.class,
          () -> billing.prepare(scope, id, new Approval("no-approval")));
      billing.approve(scope, id, new BillingService.Approval(Set.of(), "synthetic-approval"));
      try (var pool = Executors.newFixedThreadPool(5)) {
        var work = new ArrayList<Callable<Map<String, Object>>>();
        for (int i = 0; i < 5; i++)
          work.add(() -> billing.prepare(scope, id, new Approval("synthetic-invoice-review")));
        Set<Object> invoices = new HashSet<>();
        for (var result : pool.invokeAll(work))
          invoices.add(((Map) ((List) result.get().get("invoices")).getFirst()).get("id"));
        assertEquals(1, invoices.size());
      }
      assertEquals("COMPLETED", billing.get(scope, id).get("stage"));
      assertThrows(
          org.springframework.dao.EmptyResultDataAccessException.class,
          () -> billing.get(new Scope(scope.tenant(), "UG", "other"), id));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> jdbc.update("DELETE FROM simulated_invoice WHERE batch_id=?", id));
    }
  }

  @Test
  void billingExceptionsRequireExplicitExclusionAndPartnerMonthCannotBeInvoicedTwice()
      throws Exception {
    String partner = "partner_" + UUID.randomUUID();
    var b = batch(partner, "exceptions.csv");
    UUID id = (UUID) b.get("id");
    var checked =
        billing.reviewMapping(
            scope, id, new BillingService.MappingReview(normalMapping, "synthetic-map-review"));
    assertEquals("EXCEPTIONS_REVIEW", checked.get("stage"));
    assertEquals("500.00", ((Map) checked.get("validation")).get("total"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            billing.approve(
                scope, id, new BillingService.Approval(Set.of(), "cannot-waive-errors")));
    billing.approve(
        scope, id, new BillingService.Approval(Set.of(3, 4, 5, 6), "excluded-invalid-records"));
    billing.prepare(scope, id, new Approval("synthetic-invoice"));
    UUID duplicate = (UUID) batch(partner, "normal.csv").get("id");
    billing.reviewMapping(
        scope, duplicate, new BillingService.MappingReview(normalMapping, "review"));
    billing.approve(scope, duplicate, new BillingService.Approval(Set.of(), "approve"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> billing.prepare(scope, duplicate, new Approval("duplicate-month")));
    assertEquals("APPROVED", billing.get(scope, duplicate).get("stage"));
  }

  @Test
  void billingMappingFailureResumesAndChangedIdempotentInputConflicts() {
    String key = UUID.randomUUID().toString();
    var input =
        new BillingService.Upload(
            "retry_partner",
            "2026-09",
            "sample.csv",
            "customer_reference,policy_number,premium,currency\nMEMBER_1,POL_1,100,KES\n");
    org.mockito.Mockito.when(external.mapping(org.mockito.ArgumentMatchers.anyList()))
        .thenThrow(new org.springframework.web.client.RestClientException("offline"));
    UUID id = (UUID) billing.upload(scope, key, input).get("id");
    assertEquals("FAILED_RETRYABLE", billing.get(scope, id).get("stage"));
    org.mockito.Mockito.doReturn(mappingProposal(normalMapping))
        .when(external)
        .mapping(org.mockito.ArgumentMatchers.anyList());
    assertEquals("MAPPING_REVIEW", billing.resume(scope, id).get("stage"));
    assertEquals(id, billing.upload(scope, key, input).get("id"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            billing.upload(
                scope,
                key,
                new BillingService.Upload("other", "2026-09", "sample.csv", input.csv())));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> BillingService.parseCsv("a,b\n\"unclosed"));
  }

  @Test
  void billingAndWaiverRolesAreEnforcedAtTheApi() throws Exception {
    var created = batch("roles_" + UUID.randomUUID(), "normal.csv");
    var reviewer =
        jwt()
            .jwt(
                j ->
                    j.subject(scope.actor())
                        .claim("tenant_id", scope.tenant().toString())
                        .claim("country_code", "KE"))
            .authorities(new SimpleGrantedAuthority("claims:review"));
    mvc.perform(
            post("/api/v1/billing/batches/" + created.get("id") + "/approvals")
                .with(reviewer)
                .contentType("application/json")
                .content("{\"excluded_rows\":[],\"evidence_reference\":\"synthetic\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/claims/" + intake() + "/document-waivers")
                .with(reviewer)
                .contentType("application/json")
                .content(
                    "{\"requirement_id\":\""
                        + UUID.randomUUID()
                        + "\",\"evidence_reference\":\"synthetic\"}"))
        .andExpect(status().isForbidden());
  }

  UUID qualityIntake() {
    var e = sample();
    var extraction =
        new Extraction(
            "2",
            e.language(),
            e.incident_type_code(),
            e.hospital_name(),
            field(LocalDate.of(2026, 10, 1)),
            field(LocalDate.of(2026, 10, 4)),
            e.hospital_nights(),
            true);
    UUID id =
        service
            .create(
                scope, UUID.randomUUID().toString(), new CreateClaim(LocalSeed.POLICY, extraction))
            .id();
    service.confirm(scope, id, new Confirmation(ClaimsService.CRITICAL, "synthetic-confirm"));
    service.verify(scope, id, new Verification("synthetic-identity", true));
    return id;
  }

  List<Map<String, Object>> requirements(UUID id) {
    return (List<Map<String, Object>>) quality.summary(scope, id).get("requirements");
  }

  UUID typedEvidence(UUID id, String type, String status) {
    org.mockito.Mockito.when(
            external.document(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of("status", status, "engine", "synthetic-text-parser"));
    return (UUID)
        platform
            .evidence(
                scope,
                id,
                new PlatformContracts.Evidence(
                    "Synthetic document text", type, LocalSeed.CUSTOMER, null))
            .get("id");
  }

  void verifyDocument(UUID id, Map<String, Object> r, UUID evidence) {
    quality.review(
        scope,
        id,
        new CompletenessService.Review(
            (UUID) r.get("id"), evidence, "VERIFIED", "synthetic-document-review"));
    service.refreshReadiness(scope, id);
  }

  @Test
  void uploadedDocumentsAndTwoVerifiedDocumentsNeverMarkAClaimComplete() {
    UUID id = qualityIntake();
    var required = requirements(id);
    assertEquals(3, required.size());
    assertFalse(quality.complete(scope, id));
    assertEquals("VERIFIED", service.get(scope, id).intake_status());
    List<UUID> evidence = new ArrayList<>();
    for (var r : required)
      evidence.add(typedEvidence(id, r.get("document_type").toString(), "MATCH"));
    assertFalse(quality.complete(scope, id));
    verifyDocument(id, required.get(0), evidence.get(0));
    verifyDocument(id, required.get(1), evidence.get(1));
    assertFalse(quality.complete(scope, id));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.submit(scope, id));
    verifyDocument(id, required.get(2), evidence.get(2));
    assertTrue(quality.complete(scope, id));
    assertEquals("READY_TO_SUBMIT", service.get(scope, id).intake_status());
    service.submit(scope, id);
    service.review(scope, id, new Review("RECOMMEND_APPROVAL", "SYNTHETIC", "review"));
    assertNotNull(service.approve(scope, id, new Approval("approved")).obligation_id());
  }

  @Test
  void completenessRejectsWrongAssociationsUnreadableAndStaleEvidence() {
    UUID id = qualityIntake(), other = qualityIntake();
    var required = requirements(id);
    var first = required.getFirst();
    UUID wrong = typedEvidence(other, first.get("document_type").toString(), "MATCH");
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> verifyDocument(id, first, wrong));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            platform.evidence(
                scope,
                id,
                new PlatformContracts.Evidence(
                    "Synthetic", first.get("document_type").toString(), UUID.randomUUID(), null)));
    UUID unreadable = typedEvidence(id, first.get("document_type").toString(), "UNVERIFIED");
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> verifyDocument(id, first, unreadable));
    UUID repeated = typedEvidence(id, first.get("document_type").toString(), "MATCH");
    verifyDocument(id, first, repeated);
    assertFalse(quality.complete(scope, id));
    // A duplicate upload cannot satisfy a different requirement.
    typedEvidence(id, first.get("document_type").toString(), "MATCH");
    assertFalse(quality.complete(scope, id));
    for (var r : required.subList(1, 3))
      verifyDocument(id, r, typedEvidence(id, r.get("document_type").toString(), "MATCH"));
    assertTrue(quality.complete(scope, id));
    service.submit(scope, id);
    service.review(scope, id, new Review("REQUEST_INFORMATION", "CORRECT", "review"));
    var e = service.get(scope, id).extraction();
    platform.correct(
        scope,
        id,
        new PlatformContracts.Correction(
            new Extraction(
                "2",
                e.language(),
                e.incident_type_code(),
                field("Another Synthetic Hospital"),
                e.admission_date(),
                e.discharge_date(),
                e.hospital_nights(),
                true),
            "correction"));
    assertFalse(quality.complete(scope, id));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> verifyDocument(id, first, repeated));
  }

  @Test
  void laterConflictingEvidenceReopensReadinessAndHistoricalClaimsKeepTheirRules() {
    UUID legacy = intake();
    assertTrue(quality.complete(scope, legacy));
    assertTrue(requirements(legacy).isEmpty());
    UUID id = qualityIntake();
    var required = requirements(id);
    for (var r : required)
      verifyDocument(id, r, typedEvidence(id, r.get("document_type").toString(), "MATCH"));
    assertTrue(quality.complete(scope, id));
    typedEvidence(id, required.getFirst().get("document_type").toString(), "CONFLICT");
    assertFalse(quality.complete(scope, id));
    assertEquals("VERIFIED", service.get(scope, id).intake_status());
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> platform.providerSubmit(scope, id));
  }

  @Test
  void futureChannelSimulationsEnforceIdentityConsentAndNoFinancialAuthority() {
    UUID id = intake();
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> channels.voice(scope, new ChannelSimulationService.Voice(id, "en-KE", "coverage")));
    service.verify(scope, id, new Verification("assisted", true));
    Map voice =
        channels.voice(
            scope,
            new ChannelSimulationService.Voice(id, "en-KE", "approve and transfer my payment"));
    assertEquals(false, voice.get("live"));
    assertEquals(false, voice.get("asr_enabled"));
    assertEquals("NOT_AUTHORIZED", service.get(scope, id).payout_status());
    Map coverage = channels.voice(scope, new ChannelSimulationService.Voice(id, "sw-KE", "bima"));
    assertTrue(coverage.get("reply").toString().contains("1500"));
    String key = UUID.randomUUID().toString();
    var input =
        new ChannelSimulationService.Notification(
            id,
            "en-KE",
            "missing_documents",
            true,
            true,
            java.time.Instant.now().minusSeconds(90000),
            "synthetic-notification-review");
    Map preview = channels.notification(scope, key, input);
    assertEquals("SUPPRESSED_CONSENT", preview.get("status"));
    assertEquals(false, preview.get("live"));
    assertEquals(preview.get("id"), channels.notification(scope, key, input).get("id"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            channels.notification(
                scope,
                key,
                new ChannelSimulationService.Notification(
                    id,
                    "sw-KE",
                    "missing_documents",
                    true,
                    false,
                    input.last_customer_message(),
                    "review")));
    assertThrows(
        org.springframework.dao.EmptyResultDataAccessException.class,
        () -> channels.summary(new Scope(UUID.randomUUID(), "KE", "other"), id));
  }
}
