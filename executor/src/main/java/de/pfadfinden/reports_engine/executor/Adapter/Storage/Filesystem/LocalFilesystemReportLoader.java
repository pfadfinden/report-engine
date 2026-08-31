package de.pfadfinden.reports_engine.executor.Adapter.Storage.Filesystem;

import de.pfadfinden.reports_engine.executor.Exceptions.FailedToLoadReport;
import de.pfadfinden.reports_engine.executor.Port.ReportDefinition;
import de.pfadfinden.reports_engine.executor.Port.ReportLoader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import net.sf.jasperreports.engine.fonts.FontFamily;
import net.sf.jasperreports.engine.fonts.SimpleFontExtensionHelper;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.repo.FileRepositoryPersistenceServiceFactory;
import net.sf.jasperreports.repo.FileRepositoryService;
import net.sf.jasperreports.repo.PersistenceServiceFactory;
import net.sf.jasperreports.repo.RepositoryService;

/**
 * Loads a precompiled report (report.jasper) from a directory named after the report from a base
 * directory on the local filesystem, e.g. {@code <reportsBaseDir>/<reportName>/report.jasper}.
 *
 * <p>Resources referenced by the report at fill time (a {@code <template>} pointing at a .jrtx
 * style template, a custom font family's TTF files, an image) are resolved first from that report's
 * own directory, then - if not found there - from a shared assets directory common to every report
 * (style template, fonts, logos live there by default; a report only needs its own copy of
 * something if it wants to override the shared default).
 */
public class LocalFilesystemReportLoader implements ReportLoader {

  private final File reportsBaseDir;
  private final File sharedAssetsDir;

  public LocalFilesystemReportLoader(File reportsBaseDir) {
    this(reportsBaseDir, null);
  }

  public LocalFilesystemReportLoader(File reportsBaseDir, File sharedAssetsDir) {
    this.reportsBaseDir = reportsBaseDir;
    this.sharedAssetsDir = sharedAssetsDir;
  }

  @Override
  public ReportDefinition load(String reportName) throws FailedToLoadReport {
    File reportDir = new File(reportsBaseDir, reportName);
    File jasperFile = new File(reportDir, "report.jasper");

    try {
      JasperReport report = (JasperReport) JRLoader.loadObject(jasperFile);

      SimpleJasperReportsContext jasperReportsContext = new SimpleJasperReportsContext();

      List<RepositoryService> repositoryServices = new ArrayList<>();
      repositoryServices.add(
          new FileRepositoryService(jasperReportsContext, reportDir.getAbsolutePath(), false));
      if (sharedAssetsDir != null && sharedAssetsDir.isDirectory()) {
        repositoryServices.add(
            new FileRepositoryService(
                jasperReportsContext, sharedAssetsDir.getAbsolutePath(), false));
      }
      jasperReportsContext.setExtensions(RepositoryService.class, repositoryServices);
      // The context's inherited defaults only wire up a PersistenceServiceFactory for
      // JasperReports' own DefaultRepositoryService, not for FileRepositoryService - without
      // this, FileRepositoryService.getResource(...) can never find a matching persistence
      // service and every lookup (including .jrtx template resolution) silently returns null.
      jasperReportsContext.setExtensions(
          PersistenceServiceFactory.class,
          List.of(FileRepositoryPersistenceServiceFactory.getInstance()));

      // Optional: a report (or the shared assets directory) can provide a fonts.xml
      // (JasperReports' "simple font family" descriptor format) referencing TTF files
      // by name - both are resolved via the RepositoryService chain above, so it
      // doesn't matter which directory actually has them.
      if (existsInReportOrShared(reportDir, "fonts.xml")) {
        List<FontFamily> fontFamilies =
            SimpleFontExtensionHelper.getInstance()
                .loadFontFamilies(jasperReportsContext, "fonts.xml");
        jasperReportsContext.setExtensions(FontFamily.class, fontFamilies);
      }

      return new ReportDefinition(report, jasperReportsContext);
    } catch (JRException e) {
      throw new FailedToLoadReport(e);
    }
  }

  private boolean existsInReportOrShared(File reportDir, String fileName) {
    if (new File(reportDir, fileName).isFile()) {
      return true;
    }
    return sharedAssetsDir != null && new File(sharedAssetsDir, fileName).isFile();
  }
}
