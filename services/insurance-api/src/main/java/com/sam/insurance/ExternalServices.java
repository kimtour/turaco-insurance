package com.sam.insurance;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ExternalServices {
  private final RestClient ai, simulator;

  public ExternalServices(
      @Value("${app.ai-url:http://localhost:8000}") String aiUrl,
      @Value("${app.ai-token:}") String aiToken,
      @Value("${app.simulator-url:http://localhost:9000}") String simulatorUrl,
      @Value("${app.simulator-token:}") String simulatorToken) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(30));
    ai =
        RestClient.builder()
            .baseUrl(aiUrl)
            .requestFactory(factory)
            .defaultHeader("Authorization", "Bearer " + aiToken)
            .build();
    simulator =
        RestClient.builder()
            .baseUrl(simulatorUrl)
            .requestFactory(factory)
            .defaultHeader("Authorization", "Bearer " + simulatorToken)
            .build();
  }

  public Contracts.Extraction extract(String text, String language) {
    return ai.post()
        .uri("/v1/extract-claim")
        .body(Map.of("transcript", text, "language", language))
        .retrieve()
        .body(Contracts.Extraction.class);
  }

  public Map document(String text, Contracts.Extraction extraction) {
    return ai.post()
        .uri("/v1/verify-document")
        .body(
            Map.of(
                "synthetic_text",
                text,
                "language",
                extraction.language(),
                "extraction",
                extraction))
        .retrieve()
        .body(Map.class);
  }

  public Map conversation(Scope s, UUID id, String text, String language) {
    return ai.post()
        .uri("/v1/conversations/turn")
        .body(
            Map.of(
                "tenant_id",
                s.tenant(),
                "country_code",
                s.country(),
                "conversation_id",
                id,
                "text",
                text,
                "language",
                language))
        .retrieve()
        .body(Map.class);
  }

  public Map submit(Scope s, UUID id, UUID policy) {
    return simulator
        .post()
        .uri("/sim/v1/claims")
        .body(
            Map.of(
                "tenant_id",
                s.tenant(),
                "country_code",
                s.country(),
                "internal_claim_id",
                id,
                "policy_id",
                policy))
        .retrieve()
        .body(Map.class);
  }

  public Map snapshot(Scope s, UUID id) {
    return simulator
        .get()
        .uri(
            "/sim/v1/claims/by-internal/{id}?tenant_id={tenant}&country_code={country}",
            id,
            s.tenant(),
            s.country())
        .retrieve()
        .body(Map.class);
  }
}
