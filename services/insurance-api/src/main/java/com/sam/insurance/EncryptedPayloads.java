package com.sam.insurance;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EncryptedPayloads {
  private final byte[] key;

  public EncryptedPayloads(@Value("${app.payload-key:}") String configured) {
    key = configured.isBlank() ? null : Base64.getDecoder().decode(configured);
    if (key != null && key.length != 32)
      throw new IllegalArgumentException("Payload key must be 256 bits");
  }

  public byte[] encrypt(String text, String context) {
    if (key == null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Encrypted intake storage is not configured");
    try {
      byte[] nonce = new byte[12];
      new SecureRandom().nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      byte[] body = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
      byte[] result = Arrays.copyOf(nonce, nonce.length + body.length);
      System.arraycopy(body, 0, result, nonce.length, body.length);
      return result;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Encrypted storage unavailable", e);
    }
  }

  public String decrypt(byte[] body, String context) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(body, 12)));
      cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(Arrays.copyOfRange(body, 12, body.length)), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Encrypted payload authentication failed", e);
    }
  }

  public static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String context(Scope s, UUID id) {
    return s.tenant() + ":" + s.country() + ":" + id;
  }
}
