package de.pfadfinden.report_engine.executor.Port;

import java.util.regex.Pattern;

public final class ExecutionIdFormat {

  private static final Pattern UUID_PATTERN =
      Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private ExecutionIdFormat() {}

  public static boolean isValid(String executionId) {
    return executionId != null && UUID_PATTERN.matcher(executionId).matches();
  }
}
