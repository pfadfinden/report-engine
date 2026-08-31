package de.pfadfinden.reports_engine.azure_report_executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.azure.functions.annotation.QueueOutput;
import java.util.Optional;

/**
 * HTTP entry point of the trigger/status/download API contract shared with local-report-executor:
 * POST /reports/{reportId}/executions. Records the execution as PENDING and hands the actual fill
 * off to the queue-triggered ExecuteReportFunction worker, returning immediately.
 */
public class TriggerReportExecutionFunction {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @FunctionName("TriggerReportExecution")
  public HttpResponseMessage run(
      @HttpTrigger(
              name = "req",
              methods = {HttpMethod.POST},
              route = "reports/{reportId}/executions",
              authLevel = AuthorizationLevel.FUNCTION)
          HttpRequestMessage<Optional<String>> request,
      @BindingName("reportId") String reportId,
      @QueueOutput(name = "message", queueName = "report-tasks", connection = "AzureWebJobsStorage")
          OutputBinding<String> queueMessage,
      final ExecutionContext context) {

    TriggerReportExecutionRequestBody body;
    try {
      body =
          OBJECT_MAPPER.readValue(
              request.getBody().orElse(""), TriggerReportExecutionRequestBody.class);
    } catch (Exception e) {
      return request
          .createResponseBuilder(HttpStatus.BAD_REQUEST)
          .body("Invalid request body: " + e.getMessage())
          .build();
    }

    try {
      String storageConnectionString = System.getenv("AzureWebJobsStorage");
      new TableExecutionStatusStore(storageConnectionString)
          .putPending(body.executionId(), body.outputFormat());

      ReportExecutionMessage message =
          new ReportExecutionMessage(
              body.executionId(), reportId, body.parameter(), body.outputFormat());
      queueMessage.setValue(OBJECT_MAPPER.writeValueAsString(message));
    } catch (Exception e) {
      context.getLogger().severe("Failed to trigger report execution: " + e.getMessage());
      return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    return request.createResponseBuilder(HttpStatus.ACCEPTED).build();
  }
}
