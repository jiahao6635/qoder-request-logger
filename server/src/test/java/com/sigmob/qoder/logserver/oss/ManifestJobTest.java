package com.sigmob.qoder.logserver.oss;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sigmob.qoder.logserver.ingest.SpoolWriter;

/**
 * M2 regression: a crash between "uploaded" and "meta appended" (or a restart
 * re-upload of the same object key) leaves TWO meta lines for one key in
 * uploads.jsonl. The manifest must count the key exactly once - with the
 * LATEST line, which describes the object currently in storage.
 */
class ManifestJobTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Records every put() call so the test can inspect the manifest bytes. */
    private static final class RecordingStorage implements StorageClient {
        final List<String> keys = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();

        @Override
        public void put(String key, byte[] bytes) {
            keys.add(key);
            payloads.add(bytes);
        }

        @Override
        public List<String> list(String prefix) {
            return List.of();
        }
    }

    @Test
    void duplicateKeyMetaLinesKeepOnlyTheLatestEntry(@TempDir Path temp) throws Exception {
        SpoolWriter spool = new SpoolWriter(temp.resolve("spool"), 64L << 20, 600_000L, "man", null);
        Path metaFile = spool.metaDir().resolve("uploads.jsonl");
        Files.createDirectories(spool.metaDir());

        String duplicatedKey = "logs/qoder/v1/date=2026-09-01/user=a@sigmob.com/src=qoder/part-000001-aaaa-0001.jsonl.gz";
        String otherKey = "logs/qoder/v1/date=2026-09-01/user=a@sigmob.com/src=qoder/part-000002-aaaa-0002.jsonl.gz";
        Files.writeString(metaFile, """
                {"record_date":"2026-09-01","user":"a@sigmob.com","src":"qoder","key":"%s","lines":3,"credits":1.0,"min_ts":"2026-09-01T02:00:00Z","max_ts":"2026-09-01T03:00:00Z","bytes":10}
                {"record_date":"2026-09-01","user":"a@sigmob.com","src":"qoder","key":"%s","lines":7,"credits":2.5,"min_ts":"2026-09-01T02:00:00Z","max_ts":"2026-09-01T05:00:00Z","bytes":20}
                {"record_date":"2026-09-01","user":"a@sigmob.com","src":"qoder","key":"%s","lines":4,"credits":0.5,"min_ts":"2026-09-01T04:00:00Z","max_ts":"2026-09-01T04:30:00Z","bytes":8}
                """.formatted(duplicatedKey, duplicatedKey, otherKey), StandardCharsets.UTF_8);

        RecordingStorage storage = new RecordingStorage();
        ManifestJob job = new ManifestJob(spool, storage, "logs/qoder/v1");
        job.generateFor(LocalDate.parse("2026-09-01"));

        assertThat(storage.keys)
                .containsExactly("logs/qoder/v1/_manifest/date=2026-09-01.json.gz");
        JsonNode manifest = MAPPER.readTree(gunzip(storage.payloads.get(0)));
        JsonNode user = manifest.path("users").get(0);

        // 3 meta lines, but only 2 distinct keys: the duplicated key appears once
        assertThat(user.path("files")).hasSize(2);

        // the surviving entry for the duplicated key is the LATEST one (lines=7)
        JsonNode duplicatedEntry = null;
        for (JsonNode file : user.path("files")) {
            if (duplicatedKey.equals(file.path("key").asText())) {
                duplicatedEntry = file;
            }
        }
        assertThat(duplicatedEntry).isNotNull();
        assertThat(duplicatedEntry.path("lines").asLong()).isEqualTo(7L);
        assertThat(duplicatedEntry.path("credits").asDouble()).isEqualTo(2.5);

        // totals count the deduped set: 7 + 4 (NOT 3 + 7 + 4)
        assertThat(user.path("lines_total").asLong()).isEqualTo(11L);
        assertThat(user.path("credits_total").asDouble()).isEqualTo(3.0);
        // ts range covers the deduped entries only: min 02:00, max 05:00
        assertThat(user.path("first_ts").asText()).isEqualTo("2026-09-01T02:00:00Z");
        assertThat(user.path("last_ts").asText()).isEqualTo("2026-09-01T05:00:00Z");
    }

    @Test
    void missingMetaFileIsSkippedQuietly(@TempDir Path temp) throws Exception {
        SpoolWriter spool = new SpoolWriter(temp.resolve("spool"), 64L << 20, 600_000L, "man", null);
        RecordingStorage storage = new RecordingStorage();
        new ManifestJob(spool, storage, "logs/qoder/v1").generateFor(LocalDate.parse("2026-09-01"));
        assertThat(storage.keys).isEmpty();
    }

    private static byte[] gunzip(byte[] gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
             var out = new java.io.ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
