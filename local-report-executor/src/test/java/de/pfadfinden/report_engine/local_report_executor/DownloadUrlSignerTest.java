package de.pfadfinden.report_engine.local_report_executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class DownloadUrlSignerTest {

  private final DownloadUrlSigner signer = new DownloadUrlSigner("test-signing-secret");

  @Test
  void signIsDeterministicForTheSameInputs() {
    long expires = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();

    assertEquals(signer.sign("exec-1", expires), signer.sign("exec-1", expires));
  }

  @Test
  void differentExecutionIdsProduceDifferentSignatures() {
    long expires = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();

    assertFalse(signer.sign("exec-1", expires).equals(signer.sign("exec-2", expires)));
  }

  @Test
  void validSignatureThatHasNotExpiredIsValid() {
    long expires = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();
    String signature = signer.sign("exec-1", expires);

    assertTrue(signer.isValid("exec-1", expires, signature));
  }

  @Test
  void expiredSignatureIsInvalidEvenIfCorrect() {
    long expires = Instant.now().minus(1, ChronoUnit.MINUTES).getEpochSecond();
    String signature = signer.sign("exec-1", expires);

    assertFalse(signer.isValid("exec-1", expires, signature));
  }

  @Test
  void tamperedSignatureIsInvalid() {
    long expires = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();
    String signature = signer.sign("exec-1", expires);

    assertFalse(signer.isValid("exec-1", expires, signature + "0"));
  }

  @Test
  void signatureForADifferentExecutionIdIsInvalid() {
    long expires = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();
    String signature = signer.sign("exec-1", expires);

    assertFalse(signer.isValid("exec-2", expires, signature));
  }

  @Test
  void signatureWithATamperedExpiryIsInvalid() {
    long expires = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();
    String signature = signer.sign("exec-1", expires);

    assertFalse(signer.isValid("exec-1", expires + 60, signature));
  }
}
