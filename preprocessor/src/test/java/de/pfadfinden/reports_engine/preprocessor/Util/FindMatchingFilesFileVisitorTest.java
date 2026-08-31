package de.pfadfinden.reports_engine.preprocessor.Util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindMatchingFilesFileVisitorTest {

  @Test
  void collectsOnlyFilesMatchingTheGlobAcrossSubdirectories(@TempDir File dir) throws IOException {
    Files.writeString(new File(dir, "report.jrxml").toPath(), "");
    Files.writeString(new File(dir, "notes.txt").toPath(), "");
    File subDir = new File(dir, "nested");
    subDir.mkdirs();
    Files.writeString(new File(subDir, "other.jrxml").toPath(), "");

    FindMatchingFilesFileVisitor visitor = new FindMatchingFilesFileVisitor("glob:*.jrxml");
    Files.walkFileTree(dir.toPath(), visitor);

    List<String> matchedNames =
        visitor.matchesList.stream()
            .map(path -> path.getFileName().toString())
            .collect(Collectors.toList());
    assertEquals(2, matchedNames.size());
    assertTrue(matchedNames.contains("report.jrxml"));
    assertTrue(matchedNames.contains("other.jrxml"));
  }

  @Test
  void matchesNothingWhenNoFileFitsTheGlob(@TempDir File dir) throws IOException {
    Files.writeString(new File(dir, "notes.txt").toPath(), "");

    FindMatchingFilesFileVisitor visitor = new FindMatchingFilesFileVisitor("glob:*.jrxml");
    Files.walkFileTree(dir.toPath(), visitor);

    assertEquals(List.of(), visitor.matchesList);
  }
}
