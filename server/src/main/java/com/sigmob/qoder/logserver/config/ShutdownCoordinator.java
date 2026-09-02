package com.sigmob.qoder.logserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.sigmob.qoder.logserver.ingest.SpoolWriter;
import com.sigmob.qoder.logserver.oss.OssUploader;

/**
 * Graceful shutdown coordinator. Spring stops SmartLifecycle beans from the
 * HIGHEST phase down; the embedded web server stops at
 * {@code WebServerStartStopLifecycle} (phase {@code DEFAULT_PHASE - 1024}).
 * This coordinator's phase is strictly BELOW that value, so it runs only
 * AFTER the web container fully stopped: no request can be in flight while
 * it flips the stopping flag, flushes and rotates every open segment, then
 * drains pending uploads within a 120 s budget. Anything left over stays on
 * disk and is resumed after restart.
 */
@Component
public class ShutdownCoordinator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ShutdownCoordinator.class);
    private static final long SHUTDOWN_UPLOAD_BUDGET_MS = 120_000;

    private final SpoolWriter spool;
    private final OssUploader uploader;
    private volatile boolean running = false;
    private volatile boolean stopping = false;

    public ShutdownCoordinator(SpoolWriter spool, OssUploader uploader) {
        this.spool = spool;
        this.uploader = uploader;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        stopping = true;
        log.info("shutdown: rotating spool segments, then draining uploads (budget {}s)",
                SHUTDOWN_UPLOAD_BUDGET_MS / 1000);
        try {
            spool.rotateAllSegments();
        } catch (Exception e) {
            log.error("shutdown rotation failed; segments stay as current.ndjson and are resumed on restart", e);
        }
        try {
            uploader.shutdownUpload(SHUTDOWN_UPLOAD_BUDGET_MS);
        } catch (Exception e) {
            log.error("shutdown upload drain failed; pending parts stay on disk", e);
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Strictly below WebServerStartStopLifecycle's phase (DEFAULT_PHASE - 1024):
     * a HIGHER value would stop this coordinator BEFORE the web server, rotating
     * segments while requests are still being served (the exact bug this fixes).
     */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 2049;
    }

    public boolean isStopping() {
        return stopping;
    }
}
