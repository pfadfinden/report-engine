package de.pfadfinden.report_engine.local_report_executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.pfadfinden.report_engine.executor.Port.ExecutionStatus;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionCleanerTest {

  private static final Duration RETENTION = Duration.ofHours(1);
  private static final String ID_A = "11111111-1111-1111-1111-111111111111";
  private static final String ID_B = "22222222-2222-2222-2222-222222222222";
  private static final String ID_C = "33333333-3333-3333-3333-333333333333";

  @TempDir Path dir;
  private ExecutionStore store;
  private RetentionCleaner cleaner;
  private Instant now;

  @BeforeEach
  void setUp() {
    store = new ExecutionStore();
    cleaner = new RetentionCleaner(store, dir.toFile(), RETENTION);
    now = Instant.now();
  }

  private File finished(String id, Instant finishedAt) throws IOException {
    store.createPending(id, "r");
    File file = Files.createFile(dir.resolve(id + ".pdf")).toFile();
    ExecutionState state = store.get(id);
    state.outputFile = file;
    state.finishedAt = finishedAt;
    state.status = ExecutionStatus.DONE;
    return file;
  }

  @Test
  void removesExpiredFinishedExecutionAndItsFile() throws IOException {
    File file = finished(ID_A, now.minus(RETENTION).minusSeconds(1));
    cleaner.sweep(now);
    assertNull(store.get(ID_A));
    assertFalse(file.exists());
  }

  @Test
  void keepsFreshFinishedExecution() throws IOException {
    File file = finished(ID_A, now.minus(Duration.ofMinutes(5)));
    cleaner.sweep(now);
    assertNotNull(store.get(ID_A));
    assertTrue(file.exists());
  }

  @Test
  void keepsRecentPendingButDropsStalePending() {
    store.createPending(ID_A, "r");
    cleaner.sweep(now);
    assertNotNull(store.get(ID_A));

    cleaner.sweep(now.plus(RETENTION).plusSeconds(1));
    assertNull(store.get(ID_A));
  }

  @Test
  void removesOldOrphanFilesOnly() throws IOException {
    Path orphan = Files.createFile(dir.resolve(ID_B + ".pdf"));
    Path fresh = Files.createFile(dir.resolve(ID_C + ".pdf"));
    Path foreign = Files.createFile(dir.resolve("notes.txt"));
    Path oldTime = Files.createFile(dir.resolve("also-not-an-id.pdf"));
    FileTime old = FileTime.from(now.minus(RETENTION).minusSeconds(60));
    Files.setLastModifiedTime(orphan, old);
    Files.setLastModifiedTime(foreign, old);
    Files.setLastModifiedTime(oldTime, old);

    cleaner.sweep(now);

    assertFalse(Files.exists(orphan));
    assertTrue(Files.exists(fresh));
    assertTrue(Files.exists(foreign), "files not named <executionId>.<ext> are never touched");
    assertTrue(Files.exists(oldTime));
  }

  @Test
  void doesNotDeleteFileOfTrackedExecutionAsOrphan() throws IOException {
    store.createPending(ID_A, "r");
    Path running = Files.createFile(dir.resolve(ID_A + ".pdf"));
    Files.setLastModifiedTime(running, FileTime.from(now.minus(Duration.ofDays(5))));
    // retention is measured from createdAt for the entry (fresh), so the file must survive too
    cleaner.sweep(now);
    assertTrue(Files.exists(running));
  }

  @Test
  void neverFollowsSymlinks() throws IOException {
    Path target = Files.createFile(dir.resolve("target.txt"));
    Path outside = Files.createTempDirectory(dir, "sub");
    Path link = dir.resolve(ID_B + ".pdf");
    Files.createSymbolicLink(link, target);
    Files.setLastModifiedTime(target, FileTime.from(now.minus(Duration.ofDays(5))));
    cleaner.sweep(now);
    assertTrue(Files.exists(target));
    assertTrue(Files.exists(outside));
  }

  @Test
  void storeCapEvictsOldestFinishedAndRejectsWhenAllRunning() throws IOException {
    ExecutionStore capped = new ExecutionStore(2);
    capped.createPending(ID_A, "r");
    capped.createPending(ID_B, "r");
    assertThrows(ExecutionStore.StoreFullException.class, () -> capped.createPending(ID_C, "r"));

    capped.get(ID_A).status = ExecutionStatus.DONE;
    capped.createPending(ID_C, "r");
    assertNull(capped.get(ID_A));
    assertNotNull(capped.get(ID_B));
    assertEquals(ExecutionStatus.PENDING, capped.get(ID_C).status);
  }
}
