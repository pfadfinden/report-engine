package de.pfadfinden.report_engine.executor.Observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.pfadfinden.report_engine.executor.Exceptions.FailedToExportReport;
import de.pfadfinden.report_engine.executor.Port.OutputFormat;
import de.pfadfinden.report_engine.executor.Port.ReportDefinition;
import de.pfadfinden.report_engine.executor.ReportFiller;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import net.sf.jasperreports.engine.JasperReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the decorator in isolation from JasperReports mechanics: the delegate is mocked, so
 * this only exercises TracingReportFiller's own span behavior - not FillReportService's, which is
 * exercised indirectly wherever a real fill happens (e.g. LocalFilesystemReportLoaderTest).
 */
class TracingReportFillerTest {

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

  private ReportDefinition reportDefinition(String name) {
    JasperReport report = mock(JasperReport.class);
    when(report.getName()).thenReturn(name);
    return new ReportDefinition(report, null);
  }

  @Test
  void delegatesToWrappedFillerAndRecordsASuccessfulSpan() throws Exception {
    ReportFiller delegate = mock(ReportFiller.class);
    TracingReportFiller filler = new TracingReportFiller(delegate);
    ReportDefinition reportDefinition = reportDefinition("test_report");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Connection conn = mock(Connection.class);
    HashMap<String, Object> parameters = new HashMap<>();

    filler.fill(reportDefinition, parameters, conn, output, OutputFormat.XLSX);

    verify(delegate).fill(reportDefinition, parameters, conn, output, OutputFormat.XLSX);
    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    SpanData span = spans.get(0);
    assertEquals("report.fill", span.getName());
    assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
    assertEquals("test_report", span.getAttributes().get(AttributeKey.stringKey("report.id")));
    assertEquals("xlsx", span.getAttributes().get(AttributeKey.stringKey("output.format")));
  }

  @Test
  void recordsAnErrorSpanWhenDelegateThrowsWithoutSwallowingIt() throws Exception {
    ReportFiller delegate = mock(ReportFiller.class);
    doThrow(new FailedToExportReport(new Exception("boom")))
        .when(delegate)
        .fill(any(), any(), any(), any(), any());
    TracingReportFiller filler = new TracingReportFiller(delegate);
    ReportDefinition reportDefinition = reportDefinition("test_report");

    assertThrows(
        FailedToExportReport.class,
        () ->
            filler.fill(
                reportDefinition,
                new HashMap<>(),
                mock(Connection.class),
                new ByteArrayOutputStream(),
                OutputFormat.XLSX));

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
    assertEquals(1, spans.get(0).getEvents().size());
  }
}
