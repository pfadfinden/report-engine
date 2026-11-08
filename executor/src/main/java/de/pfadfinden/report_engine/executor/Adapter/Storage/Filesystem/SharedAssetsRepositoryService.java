package de.pfadfinden.report_engine.executor.Adapter.Storage.Filesystem;

import java.io.File;
import java.util.regex.Pattern;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.repo.FileRepositoryService;

/**
 * File repository rooted at the shared assets directory that additionally understands the
 * authoring-time spelling of shared references.
 *
 * <p>Report authors edit in Jaspersoft Studio against the source tree ({@code
 * reports/<report>/report.jrxml} next to {@code reports/_shared/}), where a shared file can only be
 * referenced as {@code ../_shared/Foo.jrtx}. At runtime the shared directory is not a sibling of
 * the report directory, so such a reference is mapped here to the plain name relative to the shared
 * root. Plain names ({@code Foo.jrtx}) keep working unchanged.
 */
class SharedAssetsRepositoryService extends FileRepositoryService {

  // One or more "../" followed by the shared directory name ("_shared", or "shared").
  private static final Pattern SHARED_PREFIX = Pattern.compile("^(?:\\.\\./)+_?shared/");

  SharedAssetsRepositoryService(JasperReportsContext context, String root) {
    super(context, root, false);
  }

  static String stripSharedPrefix(String location) {
    return location == null ? null : SHARED_PREFIX.matcher(location).replaceFirst("");
  }

  @Override
  protected File locateFile(String location) {
    return super.locateFile(stripSharedPrefix(location));
  }

  @Override
  protected File locateContextFile(File contextFile, String location) {
    return super.locateContextFile(contextFile, stripSharedPrefix(location));
  }
}
