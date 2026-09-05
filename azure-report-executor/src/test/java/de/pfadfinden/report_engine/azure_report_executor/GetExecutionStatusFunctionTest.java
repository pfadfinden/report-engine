package de.pfadfinden.report_engine.azure_report_executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import de.pfadfinden.report_engine.executor.Port.ExecutionStatus;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class GetExecutionStatusFunctionTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final GetExecutionStatusFunction function = new GetExecutionStatusFunction();

  @SuppressWarnings("unchecked")
  private HttpRequestMessage<Optional<String>> request() {
    HttpRequestMessage<Optional<String>> request = mock(HttpRequestMessage.class);
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
  void invalidExecutionIdFormatReturnsBadRequest() throws Exception {
    HttpResponseMessage response = function.run(request(), "../../etc/passwd", context());

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
  }

  @Test
  void unknownExecutionIdReturnsNotFound() throws Exception {
    try (MockedConstruction<TableExecutionStatusStore> ignored =
        mockConstruction(
            TableExecutionStatusStore.class,
            (mock, mockContext) ->
                when(mock.getStatus("99999999-9999-9999-9999-999999999999"))
                    .thenReturn(Optional.empty()))) {

      HttpResponseMessage response =
          function.run(request(), "99999999-9999-9999-9999-999999999999", context());

      assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
    }
  }

  @Test
  void knownExecutionIdReturnsItsStatus() throws Exception {
    try (MockedConstruction<TableExecutionStatusStore> ignored =
        mockConstruction(
            TableExecutionStatusStore.class,
            (mock, mockContext) ->
                when(mock.getStatus("11111111-1111-1111-1111-111111111111"))
                    .thenReturn(Optional.of(ExecutionStatus.PENDING)))) {

      HttpResponseMessage response =
          function.run(request(), "11111111-1111-1111-1111-111111111111", context());

      assertEquals(HttpStatus.OK, response.getStatus());
      Map<?, ?> body = objectMapper.readValue((String) response.getBody(), Map.class);
      assertEquals("PENDING", body.get("status"));
    }
  }
}
