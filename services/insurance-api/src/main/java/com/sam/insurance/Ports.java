package com.sam.insurance;

import java.time.LocalDate;
import java.util.*;

public final class Ports {
  private Ports() {}

  public interface PolicyPort {
    Map<String, Object> findPolicy(Scope scope, UUID id);

    Map<String, Object> listBenefits(Scope scope, UUID policyId, String incidentCode);

    boolean checkActive(Map<String, Object> policy, LocalDate admission, LocalDate discharge);
  }

  public interface CustomerPort {
    Map<String, Object> findCustomer(Scope scope, UUID id);

    UUID createCustomer(Scope scope, Contracts.CustomerInput input);
  }

  public interface ProductCatalogPort {
    List<Map<String, Object>> listProducts(Scope scope);

    List<String> listIncidentTypes(Scope scope);
  }

  public interface ClaimSubmissionPort {
    String submitClaim(Scope scope, UUID id);

    String getClaimStatus(Scope scope, UUID id);
  }
}
