package com.sigmob.qoder.logserver.oss;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sigmob.qoder.logserver.config.ServerMetrics;
import com.sigmob.qoder.logserver.ingest.SpoolWriter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Ryan m4: during the shutdown drain a single failing part must not abort the
 * loop - the remaining parts still get their best-effort upload, the failed
 * one stays on disk for restart resume (budget checks still apply).
 */
class OssUploaderShutdownTest {

    /** Fails every put() whose key contains the given marker. */
    private static final class FlakyStorage implements StorageClient {
        final List<String> putKeys = new ArrayList<>();
        private final String failMarker;

        FlakyStorage(String failMarker) {
            this.failMarker = failMarker;
        }

        @Override
        public void put(String key, byte[] bytes) throws IOException {
            putKeys.add(key);
            if (key.contains(failMarker)) {
                throw new IOException("simulated upload failure for " + key);
            }
        }

        @Override
        public List<String> list(String prefix) {
            return List.of();
        }
    }

    @Test
    void shutdownDrainContinuesAfterAFailingPart(@TempDir Path temp) throws Exception {
        SpoolWriter spool = new SpoolWriter(temp.resolve("spool"), 64L << 20, 600_000L, "sd", null);
        Path dir = spool.spoolDir().resolve("date=2026-09-01").resolve("user=u@sigmob.com").resolve("src=qoder");
        Files.createDirectories(dir);
        // scanPendingParts sorts by file name: 0001 is attempted before 0002
        Path first = dir.resolve("part-000001-aaaa-0001.ndjson");
        Path second = dir.resolve("part-000002-aaaa-0002.ndjson");
        Files.writeString(first, "{\"a\":1}\n", StandardCharsets.UTF_8);
        Files.writeString(second, "{\"b\":2}\n", StandardCharsets.UTF_8);

        FlakyStorage storage = new FlakyStorage("part-000001");
        ServerMetrics metrics = new ServerMetrics(new SimpleMeterRegistry());
        OssUploader uploader = new OssUploader(spool, storage, metrics, "logs/qoder/v1",
                new SimpleMeterRegistry());

        uploader.shutdownUpload(10_000);

        assertThat(storage.putKeys).as("both parts must be attempted").hasSize(2);
        assertThat(first).as("the failing part stays on disk for restart resume").exists();
        assertThat(second).as("the later part is still drained (uploaded and removed)").doesNotExist();
    }
}
