package com.sigmob.qoder.logserver.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.SmartLifecycle;

import com.sigmob.qoder.logserver.ingest.SpoolWriter;
import com.sigmob.qoder.logserver.oss.FileSystemStorageClient;
import com.sigmob.qoder.logserver.oss.OssUploader;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Ryan m2: Spring stops SmartLifecycle beans from the HIGHEST phase down and
 * the embedded web server stops at {@code WebServerStartStopLifecycle}
 * (phase {@code DEFAULT_PHASE - 1024}). The coordinator must sit strictly
 * BELOW that value, otherwise it rotates segments while requests - and their
 * appends - are still in flight.
 */
class ShutdownCoordinatorTest {

    @Test
    void phaseStopsAfterTheWebServerLifecycle(@TempDir Path temp) {
        SpoolWriter spool = new SpoolWriter(temp.resolve("spool"), 64L << 20, 600_000L, "sc", null);
        FileSystemStorageClient storage = new FileSystemStorageClient(temp.resolve("storage"));
        ServerMetrics metrics = new ServerMetrics(new SimpleMeterRegistry());
        OssUploader uploader = new OssUploader(spool, storage, metrics, "logs/qoder/v1",
                new SimpleMeterRegistry());
        ShutdownCoordinator coordinator = new ShutdownCoordinator(spool, uploader);

        int webServerStopPhase = SmartLifecycle.DEFAULT_PHASE - 1024; // WebServerStartStopLifecycle
        assertThat(coordinator.getPhase())
                .as("coordinator must stop strictly after the web server (smaller phase)")
                .isLessThan(webServerStopPhase);
        assertThat(coordinator.getPhase())
                .as("but still within a sane range, not Integer.MIN_VALUE collapse")
                .isGreaterThan(SmartLifecycle.DEFAULT_PHASE - 10_000);
    }
}
