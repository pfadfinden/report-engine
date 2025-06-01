package de.pfadfinden.reports_engine.preprocessor;

import java.io.File;
import java.io.IOException;
import org.apache.commons.io.FileUtils;

import de.pfadfinden.reports_engine.preprocessor.Adapter.SqliteMetadataRepository;
import de.pfadfinden.reports_engine.preprocessor.Adapter.YamlMetadataReader;
import de.pfadfinden.reports_engine.preprocessor.Port.MetadataRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

@Command(name = "preprocess", commandListHeading = "%nTasks:%n", description = "%nPreprocesses report definitions from a source directory for subsequent deployment. Different processing tasks can be selected by providing them as arguments.%n", mixinStandardHelpOptions = true, subcommandsRepeatable = true, versionProvider = ManifestVersionProvider.class, synopsisSubcommandLabel = "[<TASKS>...]")
public class DefaultCommand implements Runnable {

    @Option(names = {
            "--source-dir" }, defaultValue = "./reports", description = "Path to directory with report definitions (default: '${DEFAULT-VALUE}')", scope = ScopeType.INHERIT)
    private File sourceDir;

    public File inputDir() {
        return sourceDir;
    }

    @Mixin
    protected TaskOptions options;

    @Command()
    public void clean() {
        System.out.println("Cleaning output directory...");
        if (this.options.outputDir().exists()) {
            // -- the directory exists, therefore reset (delete all contents)
            try {
                FileUtils.deleteDirectory(this.options.outputDir());
            } catch (IOException e) {
                System.out.println(
                        "the output directory exists, but there was an issue with it. Make sure it is a directory and not a file and writable.");
                throw new RuntimeException(e);
            }
            this.options.outputDir().delete();
        }
        System.out.println("Finished cleaning output directory");
    }

    @Command(name = "read-metadata")
    public void readMetadata() {
        System.out.println("Ingesting report metadata into database...");

        this.options.outputDir().mkdirs();
        MetadataRepository metadataRepository = SqliteMetadataRepository.initNew(this.options.outputDir().getPath());
        MetadataIngestService preprocessor = new MetadataIngestService(metadataRepository, new YamlMetadataReader());

        preprocessor.ingestFrom(this.inputDir());

        System.out.println("Finished ingesting report metadata.");
    }

    /**
     * This method is executed if the preprocessor is called without any arguments.
     * It calls default tasks that should be executed.
     */
    @Override
    public void run() {
        this.clean();
        this.readMetadata();
    }
}