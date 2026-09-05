package de.pfadfinden.report_engine.executor.Observability;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Tallies bytes written to the wrapped stream, so a caller can report output size for its own audit
 * log line/metric without FillReportService needing to know or return it.
 */
public class CountingOutputStream extends FilterOutputStream {

  private long count = 0;

  public CountingOutputStream(OutputStream out) {
    super(out);
  }

  @Override
  public void write(int b) throws IOException {
    out.write(b);
    count++;
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    out.write(b, off, len);
    count += len;
  }

  public long bytesWritten() {
    return count;
  }
}
