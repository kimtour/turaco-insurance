package com.sam.insurance;

import static org.springframework.http.HttpStatus.FORBIDDEN;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

public record Scope(UUID tenant, String country, String actor) {
  public static Scope from(Jwt jwt) {
    try {
      UUID tenant = UUID.fromString(jwt.getClaimAsString("tenant_id"));
      String country = jwt.getClaimAsString("country_code");
      if (country == null || !country.matches("KE|UG|NG|GH|ZM") || jwt.getSubject() == null)
        throw new IllegalArgumentException();
      return new Scope(tenant, country, jwt.getSubject());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(FORBIDDEN, "Missing authenticated tenant/country scope");
    }
  }
}
