package de.pfadfinden.reports_engine.azure_report_executor;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import de.pfadfinden.reports_engine.executor.Port.ExecutionStatus;

/** GET /executions/{executionId}/status - part of the shared trigger/status/download API contract. */
public class GetExecutionStatusFunction {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @FunctionName("GetExecutionStatus")
    public HttpResponseMessage run(
            @HttpTrigger(
                name = "req",
                methods = {HttpMethod.GET},
                route = "executions/{executionId}/status",
                authLevel = AuthorizationLevel.FUNCTION)
                HttpRequestMessage<Optional<String>> request,
            @BindingName("executionId") String executionId,
            final ExecutionContext context) throws Exception {

        String storageConnectionString = System.getenv("AzureWebJobsStorage");
        Optional<ExecutionStatus> status = new TableExecutionStatusStore(storageConnectionString)
                .getStatus(executionId);

        if (status.isEmpty()) {
            return request.createResponseBuilder(HttpStatus.NOT_FOUND).build();
        }

        return request.createResponseBuilder(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(OBJECT_MAPPER.writeValueAsString(Map.of("status", status.get().name())))
                .build();
    }
}
