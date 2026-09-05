package de.pfadfinden.report_engine.executor.Observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class CountingOutputStreamTest {

  @Test
  void tallyStartsAtZero() {
    CountingOutputStream out = new CountingOutputStream(new ByteArrayOutputStream());

    assertEquals(0, out.bytesWritten());
  }

  @Test
  void singleByteWritesAreCounted() throws IOException {
    CountingOutputStream out = new CountingOutputStream(new ByteArrayOutputStream());

    out.write('a');
    out.write('b');
    out.write('c');

    assertEquals(3, out.bytesWritten());
  }

  @Test
  void bulkWritesAreCountedByLength() throws IOException {
    ByteArrayOutputStream underlying = new ByteArrayOutputStream();
    CountingOutputStream out = new CountingOutputStream(underlying);
    byte[] data = "hello world".getBytes();

    out.write(data);
    out.write(data, 2, 5);

    assertEquals(data.length + 5, out.bytesWritten());
    assertEquals(data.length + 5, underlying.size());
  }
}
