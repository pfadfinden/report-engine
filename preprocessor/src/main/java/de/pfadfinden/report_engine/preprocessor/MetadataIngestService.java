package de.pfadfinden.report_engine.preprocessor;

import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import de.pfadfinden.report_engine.preprocessor.Port.MetadataReader;
import de.pfadfinden.report_engine.preprocessor.Port.MetadataRepository;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.stream.Stream;

public class MetadataIngestService {

  private MetadataRepository metadataRepository;
  private MetadataReader metadataReader;

  public MetadataIngestService(
      MetadataRepository metadataRepository, MetadataReader metadataReader) {
    this.metadataRepository = metadataRepository;
    this.metadataReader = metadataReader;
  }

  public void ingestFrom(File inputDir) {
    assert inputDir.isDirectory();

    // for each directory in the inputDir, process the contained report
    File[] inputDirContents = inputDir.listFiles();
    if (inputDirContents == null || inputDirContents.length == 0) {
      throw new RuntimeException(
          "Input directory is empty. Make sure you provide the correct arguments.");
    }

    Stream.of(inputDirContents)
        .filter(file -> file.isDirectory())
        .forEach(dir -> tryToProcess(dir));
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
    if (metadataFile == null) {
      // Not every directory under the source dir is a report - e.g. "_shared"
      // holds assets (style template, fonts, logos) common to all reports.
      System.out.println(
          "Skipping " + reportDir.getName() + ": no metadata.yaml, not a report directory.");
      return;
    }

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
