package de.pfadfinden.reports_engine.preprocessor;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.stream.Stream;

import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import de.pfadfinden.reports_engine.preprocessor.Port.MetadataReader;
import de.pfadfinden.reports_engine.preprocessor.Port.MetadataRepository;

public class MetadataIngestService {

    private MetadataRepository metadataRepository;
    private MetadataReader metadataReader;

    public MetadataIngestService(
            MetadataRepository metadataRepository,
            MetadataReader metadataReader) {
        this.metadataRepository = metadataRepository;
        this.metadataReader = metadataReader;
    }

    public void ingestFrom(File inputDir) {
        assert inputDir.isDirectory();

        // for each directory in the inputDir, process the contained report
        File[] inputDirContents = inputDir.listFiles();
        if (inputDirContents == null || inputDirContents.length == 0) {
            throw new RuntimeException("Input directory is empty. Make sure you provide the correct arguments.");
        }

        Stream.of(inputDirContents).filter(file -> file.isDirectory()).forEach(dir -> tryToProcess(dir));
    }

    public void tryToProcess(File reportDir) {
        try {
            process(reportDir);
        } catch (Exception e) {
            System.out.println("Failed to process " + reportDir.getName());
            e.printStackTrace();
        }
    }

    public void process(File reportDir) throws IOException {
        assert reportDir.isDirectory();

        File metadataFile = getFileIn(reportDir, "metadata.yaml");
        ReportMetadata metadata = this.metadataReader.read(metadataFile);
        this.metadataRepository.add(metadata);

    }

    private File getFileIn(File reportDir, String withName) {
        FilenameFilter filter = (dir, name) -> name.endsWith(withName);
        File[] results = reportDir.listFiles(filter);
        File file = results.length > 0 ? results[0] : null;
        return file;
    }
}
