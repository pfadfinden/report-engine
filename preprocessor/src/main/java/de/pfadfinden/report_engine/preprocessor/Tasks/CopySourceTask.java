package de.pfadfinden.report_engine.preprocessor.Tasks;

import de.pfadfinden.report_engine.preprocessor.AbstractFollowUpTaskCommand;
import de.pfadfinden.report_engine.preprocessor.Metadata.ReportMetadata;
import de.pfadfinden.report_engine.preprocessor.Util.FindMatchingFilesFileVisitor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Copies files matching a given pattern fron the input to the output directoy
 *
 * <p>Sample usage: copy preview image
 *
 * <p>Output is the relative location and file name from the input directory, relative to the
 * outputBaseDir.
 */
@Command(name = "copy-source")
public class CopySourceTask extends AbstractFollowUpTaskCommand {

  @Option(
      names = {"--matchingGlob"},
      required = true,
      description = "glob pattern to describe which files top copy from source")
  private String pattern;

  private final List<String> failedFiles = new ArrayList<>();

  @Override
  public void run() {
    super.run();
    if (!failedFiles.isEmpty()) {
      throw new RuntimeException(
          "Failed to copy " + failedFiles.size() + " file(s): " + String.join(", ", failedFiles));
    }
  }

  public void export(ReportMetadata report) {
    List<Path> files = findMatchingFiles(pattern);

    files.forEach(
        source -> {
          File outputFile =
              new File(
                  this.options.outputDir().getPath()
                      + File.separator
                      + this.parent.inputDir().toPath().relativize(source));
          outputFile.getParentFile().mkdirs();
          try {
            Files.copy(source, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
          } catch (IOException e) {
            e.printStackTrace();
            System.out.println("Failed to copy file " + source);
            failedFiles.add(source.toString());
          }
        });
  }

  private List<Path> findMatchingFiles(String pattern) {
    try {
      FindMatchingFilesFileVisitor matcherVisitor =
          new FindMatchingFilesFileVisitor("glob:" + pattern);
      Files.walkFileTree(this.parent.inputDir().toPath(), matcherVisitor);
      return matcherVisitor.matchesList;
    } catch (IOException e) {
      throw new RuntimeException("error while finding matching files");
    }
  }
}
