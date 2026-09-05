package de.pfadfinden.report_engine.azure_report_executor;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.options.BlockBlobOutputStreamOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.blob.specialized.BlobOutputStream;
import com.azure.storage.blob.specialized.BlockBlobClient;
import java.time.OffsetDateTime;

/**
 * Durable storage for filled report output, backed by the function app's own storage account
 * (AzureWebJobsStorage). The download endpoint hands back a short-lived SAS URL to a blob here -
 * never bytes, never a redirect - to match the API contract shared with the local executor.
 */
public class BlobReportOutputStore {

  private static final String CONTAINER_NAME = "report-outputs";
  private static final long DOWNLOAD_URL_TTL_MINUTES = 15;

  private final BlobContainerClient containerClient;

  public BlobReportOutputStore(String connectionString) {
    BlobServiceClient serviceClient =
        new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
    serviceClient.createBlobContainerIfNotExists(CONTAINER_NAME);
    this.containerClient = serviceClient.getBlobContainerClient(CONTAINER_NAME);
  }

  /**
   * Opens a stream to write a report's output directly to blob storage, without buffering the whole
   * (potentially large) output in memory first. The caller closes the returned stream.
   */
  public BlobOutputStream open(String executionId, String extension, String contentType) {
    BlockBlobClient blob =
        containerClient.getBlobClient(blobName(executionId, extension)).getBlockBlobClient();
    BlobHttpHeaders headers = new BlobHttpHeaders().setContentType(contentType);
    return blob.getBlobOutputStream(new BlockBlobOutputStreamOptions().setHeaders(headers));
  }

  public String generateDownloadUrl(String executionId, String extension) {
    BlobClient blob = containerClient.getBlobClient(blobName(executionId, extension));

    OffsetDateTime expiry = OffsetDateTime.now().plusMinutes(DOWNLOAD_URL_TTL_MINUTES);
    BlobSasPermission permission = new BlobSasPermission().setReadPermission(true);
    BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(expiry, permission);

    String sasToken = blob.generateSas(sasValues);
    return blob.getBlobUrl() + "?" + sasToken;
  }

  private String blobName(String executionId, String extension) {
    return executionId + "." + extension;
  }
}
