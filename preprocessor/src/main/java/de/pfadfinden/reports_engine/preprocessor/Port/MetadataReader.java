package de.pfadfinden.reports_engine.preprocessor.Port;

import de.pfadfinden.reports_engine.preprocessor.Exception.FailedToReadReport;
import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import java.io.File;

public interface MetadataReader {
  public ReportMetadata read(File metadataFile) throws FailedToReadReport;
}
