package com.sigmob.qoder.logserver.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full pipeline under test with fast clocks (idle close 1 s, upload cycle 1 s)
 * and the filesystem storage backend: ingest -> stamp -> spool -> rotate ->
 * gzip -> "upload" -> metadata. Uses fixed record timestamps so the expected
 * object layout is fully deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpoolUploadE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "qk_e2eaaaabbbbccccddddeeeeffff0000";
    private static final String OWNER = "jiahao.li@sigmob.com";

    @TempDir
    static Path temp;

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path keys = temp.resolve("api-keys.yml");
        Files.writeString(keys, """
                keys:
                  - user_id: %s
                    key_sha256: %s
                    display_name: 李嘉豪
                    enabled: true
                """.formatted(OWNER, sha256Hex(API_KEY)));
        registry.add("audit.api-keys-file", () -> keys.toString());
        registry.add("audit.spool-dir", () -> temp.resolve("spool").toString());
        registry.add("audit.close-idle-seconds", () -> "1");
        registry.add("audit.upload-interval-seconds", () -> "1");
        registry.add("audit.rate-limit-per-ip", () -> "1000");
        // dev data volume is ~98% full; disable backpressure noise (covered elsewhere)
        registry.add("audit.disk.high-watermark", () -> "1.0");
        registry.add("oss.mode", () -> "file");
        registry.add("oss.file-storage-dir", () -> temp.resolve("storage").toString());
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static Path storageRoot() {
        return temp.resolve("storage");
    }

    /** Finds every uploaded object below the storage root matching a glob suffix. */
    private static List<Path> objectsUnder(String relativeDir, String suffix) throws IOException {
        Path dir = storageRoot().resolve(relativeDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> gunzipLines(Path gzFile) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(gzFile))),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    @Test
    void recordsFlowThroughSpoolGzipUploadWithStampsAndDedup() throws Exception {
        // 1. inject a deterministic batch: one plain record -> src=qoder,
        //    one QoderWork task record -> src=qoderwork; both 2026-09-01 Beijing.
        //    Attribution is payload-driven: the email field below decides the
        //    ingest_user stamp and the user= object partition (shared-key server).
        String batch = """
                {"log_schema":"1.0.1","record_kind":"hook_event","client_id":"CZ-0101000193/happyelements",\
                "email":"jiahao.li@sigmob.com","session_id":"15bcb426-8673","timestamp":"2026-09-01T02:09:12.883Z",\
                "timestamp_ms":1788228552883,"event":"UserPromptSubmit","type":"USER_REQUEST",\
                "prompt":"run the tests","credits":1.42}
                {"log_schema":"1.0.1","record_kind":"hook_event","client_id":"CZ-0101000193/happyelements",\
                "email":"jiahao.li@sigmob.com","session_id":"task-9f2c-longrunning","timestamp":"2026-09-01T02:09:16.279Z",\
                "timestamp_ms":1788228556279,"event":"TaskCreated","type":"TASK_CREATED",\
                "credits":0.58}
                """;
        mockMvc.perform(post("/api/logs/batch")
                        .header("X-API-Key", API_KEY)
                        .contentType("application/x-ndjson")
                        .content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(0));

        // 2. wait for rotate + upload with the expected deterministic layout
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(objectsUnder("logs/qoder/v1/date=2026-09-01/user=" + OWNER + "/src=qoder",
                    ".jsonl.gz")).hasSize(1);
            assertThat(objectsUnder("logs/qoder/v1/date=2026-09-01/user=" + OWNER + "/src=qoderwork",
                    ".jsonl.gz")).hasSize(1);
        });

        Path qoderObject = objectsUnder(
                "logs/qoder/v1/date=2026-09-01/user=" + OWNER + "/src=qoder", ".jsonl.gz").get(0);
        Path qoderworkObject = objectsUnder(
                "logs/qoder/v1/date=2026-09-01/user=" + OWNER + "/src=qoderwork", ".jsonl.gz").get(0);

        // part naming contract: part-<HHmmss>-<4hex>-<seq>.jsonl.gz
        assertThat(qoderObject.getFileName().toString()).matches("part-\\d{6}-[0-9a-f]{4}-\\d{4}\\.jsonl\\.gz");

        // 3. gunzip and verify stamped content
        List<String> qoderLines = gunzipLines(qoderObject);
        assertThat(qoderLines).hasSize(1);
        JsonNode stamped = MAPPER.readTree(qoderLines.get(0));
        assertThat(stamped.path("ingest_user").asText()).isEqualTo(OWNER);
        assertThat(stamped.path("ingest_time").asText()).isNotBlank();
        assertThat(stamped.path("prompt").asText()).isEqualTo("run the tests");

        List<String> qoderworkLines = gunzipLines(qoderworkObject);
        assertThat(qoderworkLines).hasSize(1);
        assertThat(MAPPER.readTree(qoderworkLines.get(0)).path("event").asText()).isEqualTo("TaskCreated");

        // 4. upload metadata journal is correct
        Path uploadsMeta = temp.resolve("spool").resolve("meta").resolve("uploads.jsonl");
        assertThat(uploadsMeta).isRegularFile();
        List<JsonNode> metaEntries = new ArrayList<>();
        for (String line : Files.readAllLines(uploadsMeta, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                metaEntries.add(MAPPER.readTree(line));
            }
        }
        assertThat(metaEntries).hasSize(2);
        JsonNode qoderMeta = metaEntries.stream()
                .filter(m -> m.path("src").asText().equals("qoder")).findFirst().orElseThrow();
        assertThat(qoderMeta.path("record_date").asText()).isEqualTo("2026-09-01");
        assertThat(qoderMeta.path("user").asText()).isEqualTo(OWNER);
        assertThat(qoderMeta.path("key").asText()).isEqualTo(
                "logs/qoder/v1/date=2026-09-01/user=" + OWNER + "/src=qoder/"
                        + qoderObject.getFileName().toString());
        assertThat(qoderMeta.path("lines").asLong()).isEqualTo(1);
        assertThat(qoderMeta.path("credits").asDouble()).isEqualTo(1.42);
        assertThat(qoderMeta.path("min_ts").asText()).isEqualTo("2026-09-01T02:09:12.883Z");
        assertThat(qoderMeta.path("max_ts").asText()).isEqualTo("2026-09-01T02:09:12.883Z");
        assertThat(qoderMeta.path("bytes").asLong()).isEqualTo(Files.size(qoderObject));

        // 5. re-sending the same batch is fully deduped and adds no new objects
        mockMvc.perform(post("/api/logs/batch")
                        .header("X-API-Key", API_KEY)
                        .contentType("application/x-ndjson")
                        .content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.deduped").value(2));

        // give any (wrong) rotation/upload cycle time to run before re-checking
        Thread.sleep(3000);
        assertThat(objectsUnder("logs/qoder/v1/date=2026-09-01/user=" + OWNER + "/src=qoder", ".jsonl.gz"))
                .hasSize(1);
        assertThat(objectsUnder("logs/qoder/v1/date=2026-09-01/user=" + OWNER + "/src=qoderwork", ".jsonl.gz"))
                .hasSize(1);
        assertThat(gunzipLines(qoderObject)).hasSize(1);
        assertThat(gunzipLines(qoderworkObject)).hasSize(1);

        // 6. spool is drained and health reflects a clean state
        Path spool = temp.resolve("spool");
        try (var walk = Files.walk(spool)) {
            assertThat(walk.filter(p -> p.getFileName().toString().startsWith("part-")).count()).isZero();
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health"))
                .andExpect(status().isOk())
                // "degraded" is a valid state on a nearly-full dev disk; only
                // overloaded/stopping would be wrong here
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .containsAnyOf("\"status\":\"ok\"", "\"status\":\"degraded\""))
                .andExpect(jsonPath("$.spool_pending_files").value(0))
                .andExpect(jsonPath("$.last_oss_success").isNotEmpty())
                .andExpect(jsonPath("$.received_total").value(4))
                .andExpect(jsonPath("$.deduped_total").value(2));
    }

}
