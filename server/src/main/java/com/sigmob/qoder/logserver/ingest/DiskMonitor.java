package com.sigmob.qoder.logserver.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sigmob.qoder.logserver.config.ServerProperties;

/**
 * Watches free space on the spool filesystem and feeds the backpressure
 * decisions: at {@code highWatermark} ingest answers 503, at 10 points below
 * it the server only warns (and health reflects the state).
 */
@Component
public class DiskMonitor {

    private static final Logger log = LoggerFactory.getLogger(DiskMonitor.class);

    private final Path spoolDir;
    private final double highWatermark;
    private volatile boolean warned = false;

    @Autowired
    public DiskMonitor(ServerProperties properties) {
        this(Path.of(properties.getSpoolDir()), properties.getDisk().getHighWatermark());
    }

    public DiskMonitor(Path spoolDir, double highWatermark) {
        this.spoolDir = spoolDir;
        this.highWatermark = highWatermark;
    }

    /** Fraction of used space on the spool filesystem (0..1); 0 when unknown. */
    public double usedRatio() {
        try {
            var store = Files.getFileStore(spoolDir);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            if (total <= 0) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, (double) (total - usable) / total));
        } catch (IOException e) {
            log.debug("cannot query file store for {}", spoolDir, e);
            return 0.0;
        }
    }

    /** True when ingest must be refused with 503. */
    public boolean isOverloaded() {
        return usedRatio() >= highWatermark;
    }

    /** True in the warning band between the warn level and the high watermark. */
    public boolean isWarnLevel() {
        return usedRatio() >= highWatermark - 0.1 && !isOverloaded();
    }

    /** Logs the WARN once while inside the warning band. */
    public void warnIfNeeded() {
        if (isWarnLevel() && !warned) {
            warned = true;
            log.warn("spool disk usage {}% is above the warning band (watermark {}%)",
                    Math.round(usedRatio() * 100), Math.round(highWatermark * 100));
        } else if (!isWarnLevel() && warned) {
            warned = false;
            log.info("spool disk usage back below the warning band");
        }
    }
}
