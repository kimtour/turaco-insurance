package com.sam.insurance;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

class JwtValidationTest {
  String token(RSAKey key, String issuer, String audience, Instant expiry) throws Exception {
    SignedJWT signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("synthetic-actor")
                .audience(audience)
                .issueTime(new Date())
                .expirationTime(Date.from(expiry))
                .build());
    signed.sign(new RSASSASigner(key));
    return signed.serialize();
  }

  @Test
  void signaturesIssuerAudienceAndExpiryAreValidated() throws Exception {
    RSAKey key = new RSAKeyGenerator(2048).keyID("synthetic-signing-key").generate();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
    server.createContext(
        "/jwks",
        e -> {
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, jwks.length);
          e.getResponseBody().write(jwks);
          e.close();
        });
    server.start();
    try {
      String issuer = "http://synthetic-issuer.invalid";
      JwtDecoder decoder =
          new SecurityConfig()
              .jwtDecoder(
                  issuer,
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks",
                  "insurance-api");
      assertEquals(
          "synthetic-actor",
          decoder
              .decode(token(key, issuer, "insurance-api", Instant.now().plusSeconds(300)))
              .getSubject());
      assertThrows(
          JwtException.class,
          () ->
              decoder.decode(token(key, issuer, "wrong-audience", Instant.now().plusSeconds(300))));
      assertThrows(
          JwtException.class,
          () ->
              decoder.decode(
                  token(
                      key,
                      "http://wrong-issuer.invalid",
                      "insurance-api",
                      Instant.now().plusSeconds(300))));
      assertThrows(
          JwtException.class,
          () ->
              decoder.decode(token(key, issuer, "insurance-api", Instant.now().minusSeconds(300))));
      RSAKey attacker = new RSAKeyGenerator(2048).keyID("synthetic-signing-key").generate();
      assertThrows(
          JwtException.class,
          () ->
              decoder.decode(
                  token(attacker, issuer, "insurance-api", Instant.now().plusSeconds(300))));
    } finally {
      server.stop(0);
    }
  }
}
