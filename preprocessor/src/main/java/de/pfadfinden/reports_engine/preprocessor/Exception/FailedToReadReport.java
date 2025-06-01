package de.pfadfinden.reports_engine.preprocessor.Exception;

public class FailedToReadReport extends RuntimeException {
   public FailedToReadReport(String message, Throwable cause) {
      super(message, cause);
   }
}
