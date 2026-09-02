package com.sigmob.qoder.logserver.config;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Central Micrometer counters (exposed via /actuator/metrics). Gauges for the
 * spool live next to their owners ({@code SpoolWriter}, {@code OssUploader}).
 */
@Component
public class ServerMetrics {

    private final Counter receivedSingle;
    private final Counter receivedBatch;
    private final Counter deduped;
    private final Counter rejected;
    private final Counter srcFallback;
    private final Counter ossUploadSuccess;
    private final Counter ossUploadFailure;

    public ServerMetrics(MeterRegistry registry) {
        this.receivedSingle = Counter.builder("records_received_total")
                .tag("endpoint", "single").description("Records received on POST /api/logs")
                .register(registry);
        this.receivedBatch = Counter.builder("records_received_total")
                .tag("endpoint", "batch").description("Records received on POST /api/logs/batch")
                .register(registry);
        this.deduped = Counter.builder("records_deduped_total")
                .description("Records dropped as duplicates").register(registry);
        this.rejected = Counter.builder("records_rejected_total")
                .description("Records rejected as malformed").register(registry);
        this.srcFallback = Counter.builder("records_src_fallback_total")
                .description("Records with no session_id and no QoderWork event, "
                        + "routed to src=qoder only as a fallback")
                .register(registry);
        this.ossUploadSuccess = Counter.builder("oss_upload_total")
                .tag("result", "success").description("Uploaded segment objects").register(registry);
        this.ossUploadFailure = Counter.builder("oss_upload_total")
                .tag("result", "failure").description("Failed segment uploads").register(registry);
    }

    public void recordReceivedSingle(long lines) {
        receivedSingle.increment(lines);
    }

    public void recordReceivedBatch(long lines) {
        receivedBatch.increment(lines);
    }

    public void recordDeduped() {
        deduped.increment();
    }

    public void recordRejected() {
        rejected.increment();
    }

    public void recordSrcFallback() {
        srcFallback.increment();
    }

    public void recordOssUpload(boolean success) {
        (success ? ossUploadSuccess : ossUploadFailure).increment();
    }

    public long receivedTotal() {
        return (long) (receivedSingle.count() + receivedBatch.count());
    }

    public long dedupedTotal() {
        return (long) deduped.count();
    }

    public long rejectedTotal() {
        return (long) rejected.count();
    }
}
