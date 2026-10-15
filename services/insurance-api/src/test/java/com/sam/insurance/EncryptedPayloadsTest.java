package com.sam.insurance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EncryptedPayloadsTest {
  @Test
  void ciphertextRequiresTheOriginalTenantResourceContextAndRejectsTampering() {
    var encryption = new EncryptedPayloads("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    byte[] ciphertext = encryption.encrypt("synthetic-private-evidence", "tenant-a:KE:evidence-a");
    assertEquals(
        "synthetic-private-evidence", encryption.decrypt(ciphertext, "tenant-a:KE:evidence-a"));
    assertThrows(
        IllegalStateException.class,
        () -> encryption.decrypt(ciphertext, "tenant-b:KE:evidence-a"));
    ciphertext[ciphertext.length - 1] ^= 1;
    assertThrows(
        IllegalStateException.class,
        () -> encryption.decrypt(ciphertext, "tenant-a:KE:evidence-a"));
  }
}
