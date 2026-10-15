package com.sam.insurance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ModeGuard {
  public ModeGuard(@Value("${app.mode}") String mode) {
    if (!"standalone".equals(mode))
      throw new IllegalStateException(
          "Integration mode is disabled: verified Turaco contracts and credentials are required");
  }
}
