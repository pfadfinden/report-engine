package de.pfadfinden.report_engine.executor.Observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CredentialRedactionTest {

  @Test
  void redactsQueryParamStyleCredentials() {
    String message =
        "Connection to jdbc:postgresql://host:5432/db?user=hitobito&password=hitobito failed";

    assertEquals(
        "Connection to jdbc:postgresql://host:5432/db?user=REDACTED&password=REDACTED failed",
        CredentialRedaction.redactJdbcCredentials(message));
  }

  @Test
  void redactsUserinfoStyleCredentials() {
    String message = "Failed to connect to postgresql://hitobito:hitobito@host:5432/db";

    assertEquals(
        "Failed to connect to postgresql://REDACTED@host:5432/db",
        CredentialRedaction.redactJdbcCredentials(message));
  }

  @Test
  void leavesMessagesWithoutCredentialsUnchanged() {
    String message = "Connection to host:5432 refused";

    assertEquals(message, CredentialRedaction.redactJdbcCredentials(message));
  }

  @Test
  void returnsNullForNullInput() {
    assertNull(CredentialRedaction.redactJdbcCredentials(null));
  }
}
