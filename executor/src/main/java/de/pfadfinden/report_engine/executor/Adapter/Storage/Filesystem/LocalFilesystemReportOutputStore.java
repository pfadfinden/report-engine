package de.pfadfinden.report_engine.executor.Adapter.Storage.Filesystem;

import de.pfadfinden.report_engine.executor.Exceptions.FailedToStoreReport;
import de.pfadfinden.report_engine.executor.Port.ReportOutputStore;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Writes filled report output to a path on the local filesystem. */
public class LocalFilesystemReportOutputStore implements ReportOutputStore {

  @Override
  public OutputStream open(String outputName) throws FailedToStoreReport {
    try {
      return new FileOutputStream(outputName);
    } catch (FileNotFoundException e) {
      throw new FailedToStoreReport(e);
    }
  }
}
