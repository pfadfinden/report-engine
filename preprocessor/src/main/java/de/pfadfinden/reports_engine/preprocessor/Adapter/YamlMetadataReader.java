package de.pfadfinden.reports_engine.preprocessor.Adapter;

import java.io.File;
import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import de.pfadfinden.reports_engine.preprocessor.Exception.FailedToReadReport;
import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import de.pfadfinden.reports_engine.preprocessor.Port.MetadataReader;

/**
 * Ingests YAML-based metadata from files into the ReportMetadata object
 * structure.
 * 
 * Uses the com.fasterxml.jackson library. ReportMetadata is annotated to enable
 * import.
 */
public class YamlMetadataReader implements MetadataReader {

    private ObjectMapper objectMapper;

    @Override
    public ReportMetadata read(File metadataFile) {
        assert metadataFile.exists();

        try {
            return this.objectMapper().readValue(metadataFile, ReportMetadata.class);
        } catch (IOException e) {
            throw new FailedToReadReport("could not read metadata.yaml", e);
        }
    }

    private ObjectMapper objectMapper() {
        if (!(this.objectMapper instanceof ObjectMapper)) {
            this.objectMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
        }
        return this.objectMapper;
    }

}
