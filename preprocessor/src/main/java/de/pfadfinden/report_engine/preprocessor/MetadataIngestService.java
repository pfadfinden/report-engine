package de.pfadfinden.report_engine.preprocessor;

import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import de.pfadfinden.report_engine.preprocessor.Port.MetadataReader;
import de.pfadfinden.report_engine.preprocessor.Port.MetadataRepository;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    List<String> failedReports = new ArrayList<>();
    Stream.of(inputDirContents)
        .filter(file -> file.isDirectory())
        .forEach(dir -> tryToProcess(dir, failedReports));

    if (!failedReports.isEmpty()) {
      throw new RuntimeException(
          "Failed to ingest "
              + failedReports.size()
              + " report(s): "
              + String.join(", ", failedReports));
    }
  }

  public void tryToProcess(File reportDir, List<String> failedReports) {
    try {
      process(reportDir);
    } catch (Exception e) {
      System.out.println("Failed to process " + reportDir.getName());
      e.printStackTrace();
      failedReports.add(reportDir.getName());
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
    // listFiles returns null (not an empty array) if reportDir isn't a directory or an I/O error
    // occurs reading it - without this check that case NPEs instead of being treated like "no
    // matching file found", which tryToProcess's caller already handles per-report.
    File[] results = reportDir.listFiles(filter);
    return results != null && results.length > 0 ? results[0] : null;
  }
}
