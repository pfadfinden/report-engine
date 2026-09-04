package de.pfadfinden.report_engine.azure_report_executor;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceException;
import de.pfadfinden.report_engine.executor.Port.ExecutionStatus;
import java.util.Optional;

/**
 * Durable execution status, keyed by executionId, backed by the function app's own storage account
 * (AzureWebJobsStorage) - Azure Functions are stateless/scale-out, so unlike the local executor's
 * in-memory map this has to be shared, durable state.
 *
 * <p>upsertEntity(TableEntity) defaults to a merge, not a replace - markDone/ markFailed only
 * touching status/errorMessage relies on that to leave the outputFormat set by putPending()
 * untouched for the rest of the record's life.
 */
public class TableExecutionStatusStore {

  private static final String TABLE_NAME = "reportexecutions";
  private static final String PARTITION_KEY = "execution";
  private static final String STATUS_PROPERTY = "status";
  private static final String ERROR_MESSAGE_PROPERTY = "errorMessage";
  private static final String OUTPUT_FORMAT_PROPERTY = "outputFormat";

  private final TableClient tableClient;

  public TableExecutionStatusStore(String connectionString) {
    TableServiceClient serviceClient =
        new TableServiceClientBuilder().connectionString(connectionString).buildClient();
    serviceClient.createTableIfNotExists(TABLE_NAME);
    this.tableClient = serviceClient.getTableClient(TABLE_NAME);
  }

  public void putPending(String executionId, String outputFormat) {
    tableClient.upsertEntity(
        new TableEntity(PARTITION_KEY, executionId)
            .addProperty(STATUS_PROPERTY, ExecutionStatus.PENDING.name())
            .addProperty(OUTPUT_FORMAT_PROPERTY, outputFormat));
  }

  /**
   * The format recorded at putPending time - needed at download time to pick the blob's
   * extension/content-type.
   */
  public Optional<String> getOutputFormat(String executionId) {
    try {
      TableEntity entity = tableClient.getEntity(PARTITION_KEY, executionId);
      return Optional.ofNullable((String) entity.getProperty(OUTPUT_FORMAT_PROPERTY));
    } catch (TableServiceException e) {
      if (e.getResponse().getStatusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  public void markDone(String executionId) {
    tableClient.upsertEntity(
        new TableEntity(PARTITION_KEY, executionId)
            .addProperty(STATUS_PROPERTY, ExecutionStatus.DONE.name()));
  }

  public void markFailed(String executionId, String errorMessage) {
    tableClient.upsertEntity(
        new TableEntity(PARTITION_KEY, executionId)
            .addProperty(STATUS_PROPERTY, ExecutionStatus.FAILED.name())
            .addProperty(ERROR_MESSAGE_PROPERTY, errorMessage));
  }

  public Optional<ExecutionStatus> getStatus(String executionId) {
    try {
      TableEntity entity = tableClient.getEntity(PARTITION_KEY, executionId);
      return Optional.of(ExecutionStatus.valueOf((String) entity.getProperty(STATUS_PROPERTY)));
    } catch (TableServiceException e) {
      if (e.getResponse().getStatusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }
}
