package de.pfadfinden.report_engine.executor.Port;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExecutionIdFormatTest {

  @Test
  void acceptsALowercaseUuid() {
    assertTrue(ExecutionIdFormat.isValid("550e8400-e29b-41d4-a716-446655440000"));
  }

  @Test
  void acceptsAnUppercaseUuid() {
    assertTrue(ExecutionIdFormat.isValid("550E8400-E29B-41D4-A716-446655440000"));
  }

  @Test
  void rejectsNull() {
    assertFalse(ExecutionIdFormat.isValid(null));
  }

  @Test
  void rejectsAPathTraversalAttempt() {
    assertFalse(ExecutionIdFormat.isValid("../../etc/passwd"));
  }

  @Test
  void rejectsAnArbitraryString() {
    assertFalse(ExecutionIdFormat.isValid("not-an-id"));
  }

  @Test
  void rejectsAUlid() {
    // Never actually generated (see class doc) - just guarding against silently widening
    // acceptance again without a deliberate reason to.
    assertFalse(ExecutionIdFormat.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
  }

  @Test
  void rejectsEmptyString() {
    assertFalse(ExecutionIdFormat.isValid(""));
  }
}
