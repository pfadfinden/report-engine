package de.pfadfinden.report_engine.executor.Observability;

import java.util.Map;
import java.util.Optional;

public final class AuditAttributes {

  private static final String GROUP_ID_KEY = "p_gruppe_id";

  private AuditAttributes() {}

  public static Optional<String> extractGroupId(Map<String, Object> parameters) {
    if (parameters == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(parameters.get(GROUP_ID_KEY)).map(String::valueOf);
  }
}
