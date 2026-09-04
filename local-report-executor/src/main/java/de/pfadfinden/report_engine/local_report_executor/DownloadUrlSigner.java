package de.pfadfinden.report_engine.local_report_executor;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies short-lived download URLs for the local executor's own /files endpoint, so
 * that the /executions/{id}/download response always carries a URL (never bytes/a redirect) - the
 * same shape a cloud-hosted executor would return via a real signed blob URL.
 */
public class DownloadUrlSigner {

  private static final String ALGORITHM = "HmacSHA256";

  private final byte[] secret;

  public DownloadUrlSigner(String secret) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  public String sign(String executionId, long expiresEpochSeconds) {
    String payload = payload(executionId, expiresEpochSeconds);
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(signature);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("Failed to sign download URL", e);
    }
  }

  public boolean isValid(String executionId, long expiresEpochSeconds, String signature) {
    if (Instant.now().getEpochSecond() > expiresEpochSeconds) {
      return false;
    }
    String expected = sign(executionId, expiresEpochSeconds);
    return constantTimeEquals(expected, signature);
  }

  private String payload(String executionId, long expiresEpochSeconds) {
    return executionId + ":" + expiresEpochSeconds;
  }

  private boolean constantTimeEquals(String a, String b) {
    byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
    byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
    if (aBytes.length != bBytes.length) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < aBytes.length; i++) {
      result |= aBytes[i] ^ bBytes[i];
    }
    return result == 0;
  }
}
