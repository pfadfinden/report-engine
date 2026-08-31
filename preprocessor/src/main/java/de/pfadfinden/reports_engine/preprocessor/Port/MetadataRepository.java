package de.pfadfinden.reports_engine.preprocessor.Port;

import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import java.util.stream.Stream;

public interface MetadataRepository {
  public void add(ReportMetadata report);

  public Stream<ReportMetadata> all();
}
