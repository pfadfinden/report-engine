package de.pfadfinden.report_engine.local_report_executor;

import de.pfadfinden.report_engine.executor.Port.ExecutionIdFormat;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Deletes execution state and output files older than the retention window, so neither memory nor
 * the output volume grows without bound in a long-running process.
 *
 * <p>Two passes: (1) store entries older than retention (finished, or stuck PENDING) are removed
 * together with their output file; (2) files in the output directory that match the {@code
 * <executionId>.<ext>} naming and have no store entry are removed once older than retention - these
 * are leftovers from a restart (the store is in-memory) or from failed runs. Nothing else in the
 * directory is ever touched, and symlinks are never followed.
 */
public class RetentionCleaner {

  private final ExecutionStore store;
  private final Path outputDir;
  private final Duration retention;

  public RetentionCleaner(ExecutionStore store, File outputDir, Duration retention) {
    this.store = store;
    this.outputDir = outputDir.toPath();
    this.retention = retention;
  }

  /** Runs one sweep now, then every {@code interval} on a daemon thread. */
  public ScheduledExecutorService start(Duration interval) {
    ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "retention-cleaner");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        () -> {
          try {
            sweep(Instant.now());
          } catch (Throwable t) {
            // Must not escape: an exception would silently cancel all future sweeps.
            System.err.println("retention sweep failed: " + t);
          }
        },
        0,
        interval.toMillis(),
        TimeUnit.MILLISECONDS);
    return scheduler;
  }

  public void sweep(Instant now) {
    Instant cutoff = now.minus(retention);

    for (Map.Entry<String, ExecutionState> entry : store.entries().entrySet()) {
      ExecutionState state = entry.getValue();
      Instant reference = state.finishedAt != null ? state.finishedAt : state.createdAt;
      if (reference.isAfter(cutoff)) {
        continue;
      }
      if (store.remove(entry.getKey(), state)) {
        deleteQuietly(state.outputFile);
      }
    }

    sweepOrphans(cutoff);
  }

  private void sweepOrphans(Instant cutoff) {
    if (!Files.isDirectory(outputDir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> files = Files.list(outputDir)) {
      files.forEach(path -> deleteIfOrphan(path, cutoff));
    } catch (IOException e) {
      System.err.println("retention orphan sweep failed: " + e);
    }
  }

  private void deleteIfOrphan(Path path, Instant cutoff) {
    String name = path.getFileName().toString();
    int dot = name.indexOf('.');
    if (dot <= 0 || !ExecutionIdFormat.isValid(name.substring(0, dot))) {
      return;
    }
    if (store.get(name.substring(0, dot)) != null) {
      return;
    }
    try {
      BasicFileAttributes attrs =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attrs.isRegularFile() && attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      System.err.println("could not delete orphan output " + name + ": " + e);
    }
  }

  private static void deleteQuietly(File file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file.toPath());
    } catch (IOException e) {
      System.err.println("could not delete output " + file.getName() + ": " + e);
    }
  }
}
