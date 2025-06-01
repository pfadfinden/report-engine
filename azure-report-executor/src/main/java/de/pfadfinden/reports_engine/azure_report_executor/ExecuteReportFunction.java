package de.pfadfinden.reports_engine.azure_report_executor;

import com.microsoft.azure.functions.annotation.*;
import com.microsoft.azure.functions.*;

/**
 * Azure Functions with Azure Storage Queue trigger.
 */
public class ExecuteReportFunction {
    /**
     * This function will be invoked when a new message is received at the specified path. The message contents are provided as input to this function.
     */
    @FunctionName("ExecuteReportFunction")
    public void run(
        @QueueTrigger(name = "execute-report", queueName = "report-tasks", connection = "AzureWebJobsStorage") String message,
        final ExecutionContext context
    ) {
        context.getLogger().info("Java Queue trigger function processed a message: " + message);
    }
}
