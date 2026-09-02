package com.sigmob.qoder.logserver.ingest;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sigmob.qoder.logserver.config.ServerProperties;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Local spool: synchronous appends to per-(date,user,src) segments, rotated by
 * the scheduled uploader into {@code part-*} files that are then uploaded.
 *
 * <p>Layout: {@code {spool}/date=D/user=U/src=S/current.ndjson}. Rotation
 * renames the current file atomically to
 * {@code part-<HHmmss>-<instId>-<seq>.ndjson} where HHmmss is the Beijing wall
 * clock at close time, instId is a random 4-hex id per process and seq is a
 * per-directory counter. Empty segments are deleted instead of rotated.</p>
 */
@Component
public class SpoolWriter {

    private static final Logger log = LoggerFactory.getLogger(SpoolWriter.class);
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    private static final String CURRENT_NAME = "current.ndjson";
    private static final String PART_PREFIX = "part-";
    public static final String DEAD_DIR = "dead";

    private final Path spoolDir;
    private final long rotateBytes;
    private final long closeIdleMillis;
    private final String instanceId;
    private final ConcurrentHashMap<Path, Segment> segments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, AtomicLong> sequences = new ConcurrentHashMap<>();
    /**
     * Serializes "open a new segment" vs. "rotate a segment": without it a
     * rotation could remove the map entry and, before the file is renamed,
     * an append could re-open the OLD current.ndjson in APPEND mode - those
     * bytes would then silently vanish into the renamed (or already uploaded
     * and deleted) inode. Rotation holds it across remove+close+move; opening
     * a segment holds it across the double-checked create.
     */
    private final ReentrantLock segmentLock = new ReentrantLock();

    /** Thrown inside the segment lock when the segment was closed by a rotation race. */
    static final class SegmentClosedException extends RuntimeException {}

    private static final class Segment {
        final Path file;
        final String date;
        final BufferedOutputStream out;
        volatile boolean closed;
        long bytesWritten;
        long lastWriteMs;

        Segment(Path file, String date, BufferedOutputStream out) {
            this.file = file;
            this.date = date;
            this.out = out;
            this.lastWriteMs = System.currentTimeMillis();
        }

        synchronized void appendLine(String line) throws IOException {
            if (closed) {
                throw new SegmentClosedException();
            }
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            out.write(bytes);
            out.flush(); // ACK only after the line reached the OS
            bytesWritten += bytes.length;
            lastWriteMs = System.currentTimeMillis();
        }

        synchronized void closeQuietly() {
            closed = true;
            try {
                out.close();
            } catch (IOException e) {
                log.warn("failed closing segment {}", file, e);
            }
        }

        synchronized boolean stale(long nowMs, long rotateBytes, long idleMillis, String today) {
            return bytesWritten >= rotateBytes
                    || nowMs - lastWriteMs >= idleMillis
                    || !date.equals(today);
        }
    }

    @Autowired
    public SpoolWriter(ServerProperties properties, MeterRegistry registry) {
        this(Path.of(properties.getSpoolDir()),
                properties.getRotateSizeMb() * 1024L * 1024L,
                properties.getCloseIdleSeconds() * 1000L,
                randomInstanceId(),
                registry);
    }

    public SpoolWriter(Path spoolDir, long rotateBytes, long closeIdleMillis, String instanceId,
                       MeterRegistry registry) {
        this.spoolDir = spoolDir;
        this.rotateBytes = rotateBytes;
        this.closeIdleMillis = closeIdleMillis;
        this.instanceId = instanceId;
        try {
            Files.createDirectories(spoolDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create spool dir " + spoolDir, e);
        }
        if (registry != null) {
            registry.gauge("spool_bytes", this, w -> w.spoolBytes());
            registry.gauge("spool_pending_files", this, w -> w.pendingPartCount());
            registry.gauge("open_segments", this, w -> w.segments.size());
        }
    }

    private Path segmentDir(String date, String user, String src) {
        return spoolDir.resolve("date=" + date).resolve("user=" + user).resolve("src=" + src);
    }

    /** Appends one stamped record line to the segment of its (date,user,src). */
    public void append(String date, String user, String src, String line) throws IOException {
        Path dir = segmentDir(date, user, src);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create segment dir " + dir, e);
        }
        Path current = dir.resolve(CURRENT_NAME);
        IOException ioFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Segment segment;
            try {
                segment = currentSegment(current, date);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            try {
                segment.appendLine(line);
                return;
            } catch (SegmentClosedException race) {
                // rotation closed this segment between lookup and lock; retry fresh
            } catch (IOException e) {
                ioFailure = e;
                log.error("append to {} failed (attempt {})", current, attempt + 1, e);
            }
        }
        throw ioFailure != null ? ioFailure : new IOException("segment kept rotating during append: " + current);
    }

    /**
     * Returns the live segment for {@code current}, opening it under the
     * rotation lock when absent. Opening and rotating are mutually exclusive,
     * so a freshly opened segment can never target a file that a concurrent
     * rotation is about to rename away (or already renamed and uploaded).
     */
    private Segment currentSegment(Path current, String date) {
        Segment segment = segments.get(current);
        if (segment != null && !segment.closed) {
            return segment; // fast path: common case needs no lock
        }
        segmentLock.lock();
        try {
            // double-check inside the lock: a rotation that held it before us may
            // have removed (or replaced) the segment in the meantime
            segment = segments.get(current);
            if (segment != null && !segment.closed) {
                return segment;
            }
            try {
                Segment opened = openSegment(current, date);
                segments.put(current, opened);
                return opened;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            segmentLock.unlock();
        }
    }

    private Segment openSegment(Path current, String date) throws IOException {
        // append mode: a crash may leave a current.ndjson behind; keep its data
        BufferedOutputStream out = new BufferedOutputStream(
                Files.newOutputStream(current, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                256 * 1024);
        long existing = Files.exists(current) ? Files.size(current) : 0L;
        Segment segment = new Segment(current, date, out);
        segment.bytesWritten = existing;
        return segment;
    }

    /** Rotates every open segment meeting any rotation condition; returns the new part files. */
    public List<Path> rotateStaleSegments() {
        String today = LocalDate.now(RecordNormalizer.SHANGHAI).toString();
        long now = System.currentTimeMillis();
        List<Path> rotated = new ArrayList<>();
        for (Map.Entry<Path, Segment> entry : segments.entrySet()) {
            Segment segment = entry.getValue();
            if (!segment.stale(now, rotateBytes, closeIdleMillis, today)) {
                continue;
            }
            rotate(entry.getKey(), segment, rotated);
        }
        return rotated;
    }

    /** Shutdown path: rotate everything regardless of staleness (empty segments deleted). */
    public List<Path> rotateAllSegments() {
        List<Path> rotated = new ArrayList<>();
        for (Map.Entry<Path, Segment> entry : segments.entrySet()) {
            rotate(entry.getKey(), segmentOf(entry), rotated);
        }
        return rotated;
    }

    private Segment segmentOf(Map.Entry<Path, Segment> entry) {
        return entry.getValue();
    }

    private void rotate(Path key, Segment segment, List<Path> rotated) {
        // hold the lock across remove + close + move so no append can slip in
        // between the map removal and the rename and re-open the old inode
        segmentLock.lock();
        try {
            if (!segments.remove(key, segment)) {
                return; // another thread already rotated it
            }
            synchronized (segment) {
                segment.closeQuietly();
                try {
                    if (segment.bytesWritten == 0) {
                        Files.deleteIfExists(segment.file);
                    } else {
                        rotated.add(rotateTarget(segment));
                    }
                } catch (IOException e) {
                    log.error("rotation of {} failed; data stays on disk", segment.file, e);
                }
            }
        } finally {
            segmentLock.unlock();
        }
    }

    private Path rotateTarget(Segment segment) throws IOException {
        Path dir = segment.file.getParent();
        LocalDateTime closeTime = LocalDateTime.now(RecordNormalizer.SHANGHAI);
        AtomicLong counter = sequences.computeIfAbsent(dir, d -> new AtomicLong());
        for (int spin = 0; spin < 1000; spin++) {
            String name = String.format("%s%s-%s-%04d.ndjson", PART_PREFIX,
                    closeTime.format(HHMMSS), instanceId, counter.incrementAndGet());
            Path target = dir.resolve(name);
            if (Files.exists(target)) {
                continue;
            }
            try {
                Files.move(segment.file, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(segment.file, target);
            }
            log.debug("rotated {} -> {}", segment.file, target);
            return target;
        }
        throw new IOException("cannot find free part name in " + dir);
    }

    /** All rotated part files awaiting upload, oldest first, excluding dead-lettered ones. */
    public List<Path> scanPendingParts() {
        Path dead = spoolDir.resolve(DEAD_DIR);
        try (Stream<Path> walk = Files.walk(spoolDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(PART_PREFIX))
                    .filter(p -> p.getFileName().toString().endsWith(".ndjson"))
                    .filter(p -> !p.startsWith(dead))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.error("cannot scan spool dir {}", spoolDir, e);
            return List.of();
        }
    }

    public long spoolBytes() {
        try (Stream<Path> walk = Files.walk(spoolDir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    public int pendingPartCount() {
        return scanPendingParts().size();
    }

    public int openSegmentCount() {
        return segments.size();
    }

    public Path spoolDir() {
        return spoolDir;
    }

    public Path metaDir() {
        return spoolDir.resolve("meta");
    }

    private static String randomInstanceId() {
        return String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
    }
}
