package com.sigmob.qoder.logserver.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sigmob.qoder.logserver.config.ServerMetrics;
import com.sigmob.qoder.logserver.config.ShutdownCoordinator;
import com.sigmob.qoder.logserver.ingest.DiskMonitor;
import com.sigmob.qoder.logserver.ingest.SpoolWriter;
import com.sigmob.qoder.logserver.oss.OssUploader;

/**
 * Liveness/readiness endpoint (no auth): one JSON object describing the state
 * of the spool, the uploader and the ingest counters. Scripts use it to wait
 * for readiness; humans use it to see upload lag at a glance.
 */
@RestController
public class HealthController {

    private final SpoolWriter spool;
    private final OssUploader uploader;
    private final ServerMetrics metrics;
    private final DiskMonitor diskMonitor;
    private final ShutdownCoordinator shutdown;
    private final String storageMode;

    public HealthController(SpoolWriter spool, OssUploader uploader, ServerMetrics metrics,
                            DiskMonitor diskMonitor, ShutdownCoordinator shutdown,
                            @Value("${oss.mode:}") String storageMode) {
        this.spool = spool;
        this.uploader = uploader;
        this.metrics = metrics;
        this.diskMonitor = diskMonitor;
        this.shutdown = shutdown;
        this.storageMode = storageMode;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Instant lastSuccess = uploader.lastSuccess();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status());
        // lets ops catch a misconfigured deployment immediately ("file" in prod = no OSS!)
        body.put("storage_mode", storageMode);
        body.put("spool_bytes", spool.spoolBytes());
        body.put("spool_pending_files", spool.pendingPartCount());
        body.put("open_segments", spool.openSegmentCount());
        body.put("last_oss_success", lastSuccess == null ? null : lastSuccess.toString());
        body.put("received_total", metrics.receivedTotal());
        body.put("deduped_total", metrics.dedupedTotal());
        body.put("rejected_total", metrics.rejectedTotal());
        body.put("upload_lag_seconds", (long) uploader.uploadLagSeconds());
        body.put("disk_used_ratio", Math.round(diskMonitor.usedRatio() * 1000.0) / 1000.0);
        return body;
    }

    private String status() {
        if (shutdown.isStopping()) {
            return "stopping";
        }
        if (diskMonitor.isOverloaded()) {
            return "overloaded";
        }
        if (diskMonitor.isWarnLevel()) {
            return "degraded";
        }
        return "ok";
    }
}
