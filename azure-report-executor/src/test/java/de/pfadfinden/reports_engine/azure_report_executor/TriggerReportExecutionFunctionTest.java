package de.pfadfinden.reports_engine.azure_report_executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.OutputBinding;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

class TriggerReportExecutionFunctionTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TriggerReportExecutionFunction function = new TriggerReportExecutionFunction();

  @SuppressWarnings("unchecked")
  private HttpRequestMessage<Optional<String>> requestWithBody(String body) {
    HttpRequestMessage<Optional<String>> request = mock(HttpRequestMessage.class);
    when(request.getBody()).thenReturn(Optional.ofNullable(body));
    when(request.createResponseBuilder(any(HttpStatus.class)))
        .thenAnswer(
            invocation -> {
              HttpStatus status = invocation.getArgument(0);
              HttpResponseMessage.Builder builder = mock(HttpResponseMessage.Builder.class);
              HttpResponseMessage response = mock(HttpResponseMessage.class);
              when(response.getStatus()).thenReturn(status);
              when(builder.header(any(), any())).thenReturn(builder);
              when(builder.body(any()))
                  .thenAnswer(
                      bodyInvocation -> {
                        when(response.getBody()).thenReturn(bodyInvocation.getArgument(0));
                        return builder;
                      });
              when(builder.build()).thenReturn(response);
              return builder;
            });
    return request;
  }

  private ExecutionContext context() {
    ExecutionContext context = mock(ExecutionContext.class);
    when(context.getLogger()).thenReturn(Logger.getLogger("test"));
    return context;
  }

  @Test
  void invalidRequestBodyReturnsBadRequest() {
    HttpRequestMessage<Optional<String>> request = requestWithBody("not json");
    OutputBinding<String> queueMessage = mock(OutputBinding.class);

    HttpResponseMessage response = function.run(request, "report-1", queueMessage, context());

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
    assertTrue(((String) response.getBody()).startsWith("Invalid request body:"));
    verifyNoInteractions(queueMessage);
  }

  @Test
  void validRequestRecordsPendingStatusAndEnqueuesMessage() throws Exception {
    String requestBody =
        objectMapper.writeValueAsString(
            new TriggerReportExecutionRequestBody("exec-1", Map.of("year", 2026), "pdf"));
    HttpRequestMessage<Optional<String>> request = requestWithBody(requestBody);
    OutputBinding<String> queueMessage = mock(OutputBinding.class);

    try (MockedConstruction<TableExecutionStatusStore> storeConstruction =
        mockConstruction(TableExecutionStatusStore.class)) {

      HttpResponseMessage response = function.run(request, "report-1", queueMessage, context());

      assertEquals(HttpStatus.ACCEPTED, response.getStatus());
      assertEquals(1, storeConstruction.constructed().size());
      verify(storeConstruction.constructed().get(0)).putPending("exec-1", "pdf");

      ArgumentCaptor<String> enqueued = ArgumentCaptor.forClass(String.class);
      verify(queueMessage).setValue(enqueued.capture());
      ReportExecutionMessage message =
          objectMapper.readValue(enqueued.getValue(), ReportExecutionMessage.class);
      assertEquals("exec-1", message.executionId());
      assertEquals("report-1", message.reportId());
      assertEquals("pdf", message.outputFormat());
    }
  }

  @Test
  void statusStoreFailureReturnsInternalServerError() throws Exception {
    String requestBody =
        objectMapper.writeValueAsString(
            new TriggerReportExecutionRequestBody("exec-1", Map.of(), "pdf"));
    HttpRequestMessage<Optional<String>> request = requestWithBody(requestBody);
    OutputBinding<String> queueMessage = mock(OutputBinding.class);

    try (MockedConstruction<TableExecutionStatusStore> ignored =
        mockConstruction(
            TableExecutionStatusStore.class,
            (mock, mockContext) ->
                doThrow(new RuntimeException("storage unavailable"))
                    .when(mock)
                    .putPending(any(), any()))) {

      HttpResponseMessage response = function.run(request, "report-1", queueMessage, context());

      assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatus());
    }
  }
}
