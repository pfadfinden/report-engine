package de.pfadfinden.reports_engine.preprocessor;

import java.io.File;

import de.pfadfinden.reports_engine.preprocessor.Adapter.SqliteMetadataRepository;
import de.pfadfinden.reports_engine.preprocessor.Port.MetadataRepository;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

public class TaskOptions {

    // @Option(names = {"--source-dir"}, defaultValue = "./reports", description =
    // "Path to directory with report definitions (default: '${DEFAULT-VALUE}')",
    // scope = ScopeType.INHERIT)
    // private File sourceDir;

    @Option(names = {
            "--output-base-dir" }, defaultValue = "./dist/preprocessed", description = "root directory for all generated output (default: '${DEFAULT-VALUE}')")
    private File outputDir;

    @Option(names = { "--sqlite-db" }, defaultValue = "reports.db")
    private String sqliteDatabase;

    /*
     * public File inputDir() {
     * return sourceDir;
     * }
     */

    public File outputDir() {
        return outputDir;
    }

    public MetadataRepository metadataRepository() {
        return new SqliteMetadataRepository(this.outputDir().getPath(), this.sqliteDatabase);
    }
}
