package de.pfadfinden.reports_engine.preprocessor.Port;

import java.util.stream.Stream;

import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;

public interface MetadataRepository {
    public void add(ReportMetadata report);

    public Stream<ReportMetadata> all();
}
