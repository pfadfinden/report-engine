package de.pfadfinden.reports_engine.preprocessor.Port;

import java.io.File;

import de.pfadfinden.reports_engine.preprocessor.Exception.FailedToReadReport;
import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;

public interface MetadataReader {
    public ReportMetadata read(File metadataFile) throws FailedToReadReport;

}
