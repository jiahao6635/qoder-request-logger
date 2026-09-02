package com.sigmob.qoder.logserver.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C2 regression: appends and rotations run concurrently at full tilt; every
 * accepted line must survive somewhere in the spool (part files + the still
 * open current.ndjson). The old remove-then-move window let an append re-open
 * the OLD current.ndjson inode right between the two steps - those lines then
 * silently vanished into the renamed (and later uploaded+deleted) file.
 */
class SpoolWriterConcurrencyTest {

    private static final int THREADS = 8;
    private static final int LINES_PER_THREAD = 200;

    @Test
    void concurrentAppendAndRotateNeverLosesLines(@TempDir Path temp) throws Exception {
        // rotateBytes = 1: every non-empty segment is instantly stale, so the
        // rotator thread keeps applying maximum rotation pressure
        SpoolWriter writer = new SpoolWriter(temp.resolve("spool"), 1L, 600_000L, "conc", null);
        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS + 1);
        try {
            List<Future<?>> appends = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int id = t;
                appends.add(pool.submit(() -> {
                    for (int i = 0; i < LINES_PER_THREAD; i++) {
                        try {
                            writer.append("2026-09-01", "u@sigmob.com", "qoder",
                                    "{\"thread\":" + id + ",\"line\":" + i + "}");
                        } catch (Exception e) {
                            throw new IllegalStateException("append failed at " + id + "/" + i, e);
                        }
                    }
                }));
            }
            Future<?> rotation = pool.submit(() -> {
                while (running.get()) {
                    writer.rotateStaleSegments();
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });

            // every append must succeed (failures propagate as ExecutionException)
            for (Future<?> append : appends) {
                append.get(120, TimeUnit.SECONDS);
            }
            running.set(false);
            rotation.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // drain the tail so everything lives in part files (also proves a final
        // rotate right after heavy concurrency is still consistent)
        writer.rotateAllSegments();

        long linesOnDisk = countNdjsonLines(writer.spoolDir());
        long expected = (long) THREADS * LINES_PER_THREAD;
        assertThat(linesOnDisk)
                .as("part files + current.ndjson must hold exactly %d lines (found %d)", expected, linesOnDisk)
                .isEqualTo(expected);
    }

    private static long countNdjsonLines(Path spoolDir) throws Exception {
        long lines = 0;
        try (var walk = Files.walk(spoolDir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (p.getFileName().toString().endsWith(".ndjson")) {
                    lines += Files.readAllLines(p, StandardCharsets.UTF_8).size();
                }
            }
        }
        return lines;
    }
}
