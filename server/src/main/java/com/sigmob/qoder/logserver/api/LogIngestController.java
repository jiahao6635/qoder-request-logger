package com.sigmob.qoder.logserver.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sigmob.qoder.logserver.config.ServerMetrics;
import com.sigmob.qoder.logserver.config.ServerProperties;
import com.sigmob.qoder.logserver.ingest.DedupService;
import com.sigmob.qoder.logserver.ingest.RecordNormalizer;
import com.sigmob.qoder.logserver.ingest.SpoolWriter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The two ingest endpoints. The response of both is ALWAYS
 * {@code 200 {"accepted":N,"rejected":M,"deduped":K}} for anything the client
 * sent us - the hook client re-queues records into its outbox on every 4xx/5xx
 * (log-request.js L995), so a poison line must be counted as rejected, never
 * answered with an error status. Only infrastructure failures (oversized
 * body, spool write error) produce non-2xx, which is exactly when a client
 * retry is wanted.
 */
@RestController
public class LogIngestController {

    private static final Logger log = LoggerFactory.getLogger(LogIngestController.class);

    private enum Outcome { ACCEPTED, REJECTED, DEDUPED }

    private final ObjectMapper mapper;
    private final DedupService dedup;
    private final SpoolWriter spool;
    private final ServerMetrics metrics;
    private final long maxBodyBytes;

    /** Decompressed body exceeded audit.max-body-mb. */
    static final class PayloadTooLargeException extends RuntimeException {}

    /** Spool append failed after admission; retriable by the client. */
    static final class SpoolWriteException extends RuntimeException {}

    /**
     * Body declared gzip but could not be decompressed (corrupt/truncated).
     * The payload is unrecoverable data, NOT an infrastructure failure: a
     * non-2xx would make the client requeue and retry it forever, so it is
     * answered 200 with rejected=1 to let the cursor advance.
     */
    static final class MalformedGzipException extends RuntimeException {}

    public LogIngestController(ObjectMapper mapper, DedupService dedup, SpoolWriter spool,
                               ServerMetrics metrics, ServerProperties properties) {
        this.mapper = mapper;
        this.dedup = dedup;
        this.spool = spool;
        this.metrics = metrics;
        this.maxBodyBytes = properties.getMaxBodyMb() * 1024L * 1024L;
    }

    /** Single-record endpoint used by the shipped hook client (fire-and-forget POST). */
    @PostMapping("/api/logs")
    public ResponseEntity<Map<String, Object>> ingestSingle(HttpServletRequest request) throws IOException {
        byte[] body = readBody(request);
        long accepted = 0;
        long rejected = 0;
        long deduped = 0;
        try {
            JsonNode node = mapper.readTree(body);
            // received口径: every payload that reached parsing counts, object or not
            metrics.recordReceivedSingle(1);
            if (node != null && node.isObject()) {
                switch (processRecord(node)) {
                    case ACCEPTED -> accepted = 1;
                    case REJECTED -> rejected = 1;
                    case DEDUPED -> deduped = 1;
                }
            } else {
                rejected = 1;
                metrics.recordRejected();
            }
        } catch (JsonProcessingException e) {
            rejected = 1; // never 4xx: the client outbox must not loop on poison payloads
            metrics.recordRejected();
        }
        return acknowledge(accepted, rejected, deduped);
    }

    /** NDJSON batch endpoint; each line is validated independently (poison isolation). */
    @PostMapping("/api/logs/batch")
    public ResponseEntity<Map<String, Object>> ingestBatch(HttpServletRequest request) throws IOException {
        byte[] body = readBody(request);
        long accepted = 0;
        long rejected = 0;
        long deduped = 0;
        long received = 0;
        for (String rawLine : new String(body, StandardCharsets.UTF_8).split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue; // trailing newline / blank separator: neither accepted nor rejected
            }
            received++;
            JsonNode node = null;
            try {
                node = mapper.readTree(line);
            } catch (JsonProcessingException ignore) {
                // poisoned line: count and move on
            }
            if (node == null || !node.isObject()) {
                rejected++;
                metrics.recordRejected();
                continue;
            }
            switch (processRecord(node)) {
                case ACCEPTED -> accepted++;
                case REJECTED -> rejected++;
                case DEDUPED -> deduped++;
            }
        }
        metrics.recordReceivedBatch(received);
        return acknowledge(accepted, rejected, deduped);
    }

    private Outcome processRecord(JsonNode node) {
        RecordNormalizer.Normalized normalized = RecordNormalizer.normalize(node, Instant.now());
        if (normalized.srcFallback()) {
            metrics.recordSrcFallback();
        }
        String dedupKey = DedupService.dedupKey(node);
        if (!dedup.admit(dedupKey)) {
            metrics.recordDeduped();
            return Outcome.DEDUPED;
        }
        try {
            spool.append(normalized.date(), normalized.userSegment(), normalized.src(),
                    normalized.stamped().toString());
            return Outcome.ACCEPTED;
        } catch (IOException | UncheckedIOException e) {
            // release the dedup slot so a client retry can persist the record;
            // UncheckedIOException matters too: SpoolWriter wraps createDirectories
            // failures (and similar) unchecked, and skipping the compensation here
            // would make a retry land in dedup and the record be silently lost
            dedup.invalidate(dedupKey);
            log.error("spool append failed for record of {}", normalized.userSegment(), e);
            throw new SpoolWriteException();
        }
    }

    /**
     * Reads the request body, transparently decompressing gzip. Enforces the
     * decompressed size limit (413) while streaming, never buffering beyond it.
     * A body that DECLARES gzip but cannot be decompressed is unrecoverable
     * client data: it becomes {@link MalformedGzipException} (-> 200 rejected=1)
     * so the hook client stops retrying instead of looping forever on it.
     */
    private byte[] readBody(HttpServletRequest request) throws IOException {
        InputStream in = request.getInputStream();
        boolean gzipped = "gzip".equalsIgnoreCase(request.getHeader("Content-Encoding"));
        if (gzipped) {
            try {
                in = new GZIPInputStream(in);
            } catch (ZipException e) {
                throw new MalformedGzipException();
            }
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        byte[] chunk = new byte[32 * 1024];
        long total = 0;
        int read;
        try {
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBodyBytes) {
                    throw new PayloadTooLargeException(); // 413 even mid-decompress
                }
                buffer.write(chunk, 0, read);
            }
        } catch (IOException e) {
            if (gzipped) {
                // truncated stream / bad CRC / truncated gzip trailer: the data
                // itself is broken and no retry can fix it
                throw new MalformedGzipException();
            }
            throw e;
        }
        return buffer.toByteArray();
    }

    private static ResponseEntity<Map<String, Object>> acknowledge(long accepted, long rejected, long deduped) {
        return ResponseEntity.ok(Map.of("accepted", accepted, "rejected", rejected, "deduped", deduped));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<Map<String, String>> payloadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "payload_too_large"));
    }

    @ExceptionHandler(SpoolWriteException.class)
    public ResponseEntity<Map<String, String>> spoolWriteFailed() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "spool_write_failed"));
    }

    @ExceptionHandler(MalformedGzipException.class)
    public ResponseEntity<Map<String, Object>> malformedGzip() {
        metrics.recordRejected();
        return acknowledge(0, 1, 0);
    }
}
