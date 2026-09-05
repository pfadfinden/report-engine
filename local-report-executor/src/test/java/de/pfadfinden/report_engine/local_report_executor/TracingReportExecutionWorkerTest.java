package de.pfadfinden.report_engine.local_report_executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the decorator in isolation: the delegate is mocked, so this only exercises
 * TracingReportExecutionWorker's own span/audit/metrics behavior, not
 * DefaultReportExecutionWorker's.
 */
class TracingReportExecutionWorkerTest {

  private InMemorySpanExporter spanExporter;

  @BeforeEach
  void setUp() {
    GlobalOpenTelemetry.resetForTest();
    spanExporter = InMemorySpanExporter.create();
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build())
            .build();
    GlobalOpenTelemetry.set(sdk);
  }

  @AfterEach
  void tearDown() {
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  void delegatesAndRecordsASuccessfulSpanWithByteCount() throws Exception {
    ReportExecutionWorker delegate = mock(ReportExecutionWorker.class);
    when(delegate.execute(eq("report-1"), eq("exec-1"), any(), eq(OutputFormat.PDF), any()))
        .thenReturn(1234L);
    TracingReportExecutionWorker worker = new TracingReportExecutionWorker(delegate);
    OutputStream output = mock(OutputStream.class);

    long size = worker.execute("report-1", "exec-1", new HashMap<>(), OutputFormat.PDF, output);

    assertEquals(1234L, size);
    verify(delegate).execute("report-1", "exec-1", new HashMap<>(), OutputFormat.PDF, output);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    SpanData span = spans.get(0);
    assertEquals("report.execution", span.getName());
    assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
    assertEquals("report-1", span.getAttributes().get(AttributeKey.stringKey("report.id")));
    assertEquals("exec-1", span.getAttributes().get(AttributeKey.stringKey("execution.id")));
    assertEquals("success", span.getAttributes().get(AttributeKey.stringKey("status")));
  }

  @Test
  void recordsAnErrorSpanAndRethrowsWhenDelegateThrows() throws Exception {
    ReportExecutionWorker delegate = mock(ReportExecutionWorker.class);
    doThrow(new IllegalStateException("boom"))
        .when(delegate)
        .execute(any(), any(), any(), any(), any());
    TracingReportExecutionWorker worker = new TracingReportExecutionWorker(delegate);

    assertThrows(
        IllegalStateException.class,
        () ->
            worker.execute(
                "report-1", "exec-1", new HashMap<>(), OutputFormat.PDF, mock(OutputStream.class)));

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
    assertEquals(1, spans.get(0).getEvents().size());
  }
}
