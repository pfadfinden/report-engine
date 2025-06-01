package de.pfadfinden.reports_engine.preprocessor;

import java.io.File;
import java.io.FilenameFilter;
import java.util.stream.Stream;

import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

public abstract class AbstractFollowUpTaskCommand implements FollowUpTask {

    @ParentCommand
    protected DefaultCommand parent;

    @Mixin
    protected TaskOptions options;

    @Override
    public void run() {
        export(this.options.metadataRepository().all());
    }

    protected void export(Stream<ReportMetadata> reports) {
        reports.forEach(report -> export(report));
    }

    protected void export(ReportMetadata report) {
    };

    protected File getReportSourceFile(ReportMetadata report, String withName) {
        return getFileIn(new File(this.parent.inputDir().getPath() + File.separator + report.id), withName);
    }

    private File getFileIn(File reportDir, String withName) {
        FilenameFilter filter = (dir, name) -> name.endsWith(withName);
        File[] results = reportDir.listFiles(filter);
        File file = results != null && results.length > 0 ? results[0] : null;
        return file;
    }
}
