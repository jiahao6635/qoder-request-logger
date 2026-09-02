package com.sigmob.qoder.logserver.oss;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sigmob.qoder.logserver.config.ServerMetrics;
import com.sigmob.qoder.logserver.ingest.RecordNormalizer;
import com.sigmob.qoder.logserver.ingest.SpoolWriter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The only scheduled worker of the pipeline. Every cycle it (1) rotates
 * segments that hit a rotation condition, (2) streams every pending part
 * through gzip while collecting line/credits/timestamp statistics, (3) uploads
 * it via {@link StorageClient} and (4) appends the upload metadata consumed by
 * {@link ManifestJob}. Failed uploads retry with exponential backoff
 * (1/2/4/8/16 min, 5 retries) before being moved to the dead-letter directory.
 */
@Component
public class OssUploader {

    private static final Logger log = LoggerFactory.getLogger(OssUploader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long[] BACKOFF_MINUTES = {1, 2, 4, 8, 16};
    private static final String UPLOADS_META = "uploads.jsonl";

    /** Gzipped payload plus the statistics gathered while compressing. */
    record GzResult(byte[] bytes, long lines, double credits, Instant minTs, Instant maxTs) {}

    private static final class FailureState {
        final int attempts;
        final Instant nextAttemptAt;

        FailureState(int attempts, Instant nextAttemptAt) {
            this.attempts = attempts;
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    private final SpoolWriter spool;
    private final StorageClient storage;
    private final ServerMetrics metrics;
    private final String prefix;
    private final Path uploadsMetaFile;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final ConcurrentHashMap<Path, FailureState> failures = new ConcurrentHashMap<>();
    private volatile boolean stopped = false;

    @Autowired
    public OssUploader(SpoolWriter spool, StorageClient storage, ServerMetrics metrics,
                       @Value("${oss.prefix:logs/qoder/v1}") String prefix, MeterRegistry registry) {
        this.spool = spool;
        this.storage = storage;
        this.metrics = metrics;
        this.prefix = prefix;
        this.uploadsMetaFile = spool.metaDir().resolve(UPLOADS_META);
        registry.gauge("upload_lag_seconds", this, u -> u.uploadLagSeconds());
    }

    // interval is configured in seconds (audit.upload-interval-seconds); SpEL converts to millis
    @Scheduled(fixedDelayString = "#{${audit.upload-interval-seconds:30} * 1000}",
               initialDelayString = "#{${audit.upload-interval-seconds:30} * 1000}")
    public void scheduledCycle() {
        runCycle();
    }

    /** One full rotate + drain pass; synchronized so shutdown cannot interleave. */
    public synchronized void runCycle() {
        if (stopped) {
            return;
        }
        try {
            spool.rotateStaleSegments();
            List<Path> parts = spool.scanPendingParts();
            for (Path part : parts) {
                if (stopped) {
                    break;
                }
                uploadPart(part);
            }
        } catch (Exception e) {
            log.error("upload cycle failed", e);
        }
    }

    /** Best-effort drain during graceful shutdown; leftovers stay for restart resume. */
    public synchronized void shutdownUpload(long budgetMillis) {
        stopped = true;
        long deadline = System.currentTimeMillis() + budgetMillis;
        try {
            List<Path> parts = spool.scanPendingParts();
            for (Path part : parts) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("shutdown upload budget exhausted, {} part(s) left on disk for restart resume",
                            parts.size());
                    return;
                }
                try {
                    uploadPart(part);
                } catch (Exception e) {
                    // one bad part must not abort the drain: keep it on disk and
                    // give the remaining parts their best-effort upload
                    log.warn("shutdown upload of {} failed, keeping it on disk; continuing with the remaining parts",
                            part, e);
                }
            }
        } catch (Exception e) {
            log.error("shutdown drain failed", e);
        }
    }

    Path uploadsMetaFile() {
        return uploadsMetaFile;
    }

    private void uploadPart(Path part) {
        FailureState state = failures.get(part);
        if (state != null && Instant.now().isBefore(state.nextAttemptAt)) {
            return; // still backing off
        }
        try {
            PartInfo info = parsePartPath(part);
            GzResult gz = gzipPart(part);
            String key = OssPathPolicy.objectKey(prefix, info.date(), info.user(), info.src(),
                    part.getFileName().toString());
            storage.put(key, gz.bytes());
            Files.deleteIfExists(part);
            appendUploadMeta(info, key, gz);
            failures.remove(part);
            lastSuccess.set(Instant.now());
            metrics.recordOssUpload(true);
            log.info("uploaded {} ({} lines, {} bytes gz)", key, gz.lines(), gz.bytes().length);
        } catch (Exception e) {
            metrics.recordOssUpload(false);
            handleFailure(part, e);
        }
    }

    private void handleFailure(Path part, Exception cause) {
        int attempts = 1;
        FailureState previous = failures.get(part);
        if (previous != null) {
            attempts = previous.attempts + 1;
        }
        if (attempts > BACKOFF_MINUTES.length) {
            deadLetter(part, cause);
            return;
        }
        long backoffMillis = BACKOFF_MINUTES[attempts - 1] * 60_000L;
        failures.put(part, new FailureState(attempts, Instant.now().plusMillis(backoffMillis)));
        log.error("upload of {} failed (attempt {}/{}), retrying in {} min",
                part, attempts, BACKOFF_MINUTES.length, BACKOFF_MINUTES[attempts - 1], cause);
    }

    private void deadLetter(Path part, Exception cause) {
        try {
            Path deadDir = spool.spoolDir().resolve(SpoolWriter.DEAD_DIR);
            Files.createDirectories(deadDir);
            // keep the original directory structure to preserve date/user/src context
            Path relative = spool.spoolDir().relativize(part);
            Path target = deadDir.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            log.error("upload of {} failed after {} retries, moved to dead-letter {}", part,
                    BACKOFF_MINUTES.length, target, cause);
        } catch (IOException e) {
            log.error("cannot dead-letter {} (staying in spool)", part, e);
        } finally {
            failures.remove(part);
        }
    }

    private record PartInfo(String date, String user, String src) {}

    static PartInfo parsePartPath(Path part) {
        // {spool}/date=D/user=U/src=S/part-*.ndjson (3 levels of parent dirs)
        Path srcDir = part.getParent();
        Path userDir = srcDir == null ? null : srcDir.getParent();
        Path dateDir = userDir == null ? null : userDir.getParent();
        if (srcDir == null || userDir == null || dateDir == null) {
            throw new IllegalStateException("part file outside expected spool layout: " + part);
        }
        String src = stripPrefix(srcDir.getFileName().toString(), "src=");
        String user = stripPrefix(userDir.getFileName().toString(), "user=");
        String date = stripPrefix(dateDir.getFileName().toString(), "date=");
        return new PartInfo(date, user, src);
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    /** Streams the part through gzip while counting lines/credits/timestamp range. */
    static GzResult gzipPart(Path part) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 20);
        long lines = 0;
        double credits = 0;
        Instant minTs = null;
        Instant maxTs = null;
        try (BufferedReader reader = Files.newBufferedReader(part, StandardCharsets.UTF_8);
             GZIPOutputStream gz = new GZIPOutputStream(buffer, 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                gz.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                lines++;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    JsonNode creditsNode = node.get("credits");
                    if (creditsNode != null && creditsNode.isNumber()) {
                        credits += creditsNode.asDouble();
                    }
                    Instant ts = RecordNormalizer.parseTimestamp(node).orElse(null);
                    if (ts != null) {
                        minTs = minTs == null || ts.isBefore(minTs) ? ts : minTs;
                        maxTs = maxTs == null || ts.isAfter(maxTs) ? ts : maxTs;
                    }
                } catch (IOException ignore) {
                    // unparseable line still ships verbatim; statistics only miss it
                }
            }
        }
        return new GzResult(buffer.toByteArray(), lines, credits, minTs, maxTs);
    }

    private void appendUploadMeta(PartInfo info, String key, GzResult gz) {
        try {
            Files.createDirectories(uploadsMetaFile.getParent());
            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("record_date", info.date());
            meta.put("user", info.user());
            meta.put("src", info.src());
            meta.put("key", key);
            meta.put("lines", gz.lines());
            meta.put("credits", gz.credits());
            if (gz.minTs() != null) {
                meta.put("min_ts", gz.minTs().toString());
            }
            if (gz.maxTs() != null) {
                meta.put("max_ts", gz.maxTs().toString());
            }
            meta.put("bytes", gz.bytes().length);
            Files.writeString(uploadsMetaFile, meta.toString() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("cannot append upload metadata to {}", uploadsMetaFile, e);
        }
    }

    /** Age in seconds of the oldest pending part; 0 when the spool is drained. */
    public double uploadLagSeconds() {
        List<Path> parts = spool.scanPendingParts();
        long oldest = 0;
        for (Path part : parts) {
            try {
                long mtime = Files.getLastModifiedTime(part).toMillis();
                oldest = oldest == 0 ? mtime : Math.min(oldest, mtime);
            } catch (IOException ignore) {
                // file vanished between scan and stat
            }
        }
        if (oldest == 0) {
            return 0.0;
        }
        return Math.max(0.0, (System.currentTimeMillis() - oldest) / 1000.0);
    }

    public Instant lastSuccess() {
        return lastSuccess.get();
    }

    Map<Path, FailureState> failures() {
        return failures;
    }
}
