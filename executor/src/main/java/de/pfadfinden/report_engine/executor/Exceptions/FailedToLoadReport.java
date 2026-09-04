package de.pfadfinden.report_engine.executor.Exceptions;

public class FailedToLoadReport extends Exception {

  public FailedToLoadReport(Throwable cause) {
    super(cause);
  }

  public FailedToLoadReport(String message) {
    super(message);
  }
}
