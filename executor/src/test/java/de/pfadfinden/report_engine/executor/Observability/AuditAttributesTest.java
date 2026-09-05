package de.pfadfinden.report_engine.executor.Observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuditAttributesTest {

  @Test
  void returnsEmptyWhenGroupIdKeyMissing() {
    assertTrue(AuditAttributes.extractGroupId(Map.of("year", 2026)).isEmpty());
  }

  @Test
  void returnsEmptyForNullParameters() {
    assertTrue(AuditAttributes.extractGroupId(null).isEmpty());
  }

  @Test
  void extractsGroupId() {
    Optional<String> groupId = AuditAttributes.extractGroupId(Map.of("p_gruppe_id", 42));

    assertEquals(Optional.of("42"), groupId);
  }
}
