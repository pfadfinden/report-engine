package de.pfadfinden.reports_engine.azure_report_executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;

import de.pfadfinden.reports_engine.executor.Port.ExecutionStatus;

class GetExecutionStatusFunctionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GetExecutionStatusFunction function = new GetExecutionStatusFunction();

    @SuppressWarnings("unchecked")
    private HttpRequestMessage<Optional<String>> request() {
        HttpRequestMessage<Optional<String>> request = mock(HttpRequestMessage.class);
        when(request.createResponseBuilder(any(HttpStatus.class))).thenAnswer(invocation -> {
            HttpStatus status = invocation.getArgument(0);
            HttpResponseMessage.Builder builder = mock(HttpResponseMessage.Builder.class);
            HttpResponseMessage response = mock(HttpResponseMessage.class);
            when(response.getStatus()).thenReturn(status);
            when(builder.header(any(), any())).thenReturn(builder);
            when(builder.body(any())).thenAnswer(bodyInvocation -> {
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
    void unknownExecutionIdReturnsNotFound() throws Exception {
        try (MockedConstruction<TableExecutionStatusStore> ignored = mockConstruction(
                TableExecutionStatusStore.class,
                (mock, mockContext) -> when(mock.getStatus("missing")).thenReturn(Optional.empty()))) {

            HttpResponseMessage response = function.run(request(), "missing", context());

            assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
        }
    }

    @Test
    void knownExecutionIdReturnsItsStatus() throws Exception {
        try (MockedConstruction<TableExecutionStatusStore> ignored = mockConstruction(
                TableExecutionStatusStore.class,
                (mock, mockContext) -> when(mock.getStatus("exec-1")).thenReturn(Optional.of(ExecutionStatus.PENDING)))) {

            HttpResponseMessage response = function.run(request(), "exec-1", context());

            assertEquals(HttpStatus.OK, response.getStatus());
            Map<?, ?> body = objectMapper.readValue((String) response.getBody(), Map.class);
            assertEquals("PENDING", body.get("status"));
        }
    }
}
