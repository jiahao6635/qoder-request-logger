package com.sigmob.qoder.logserver.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * HTTP contract of the two ingest endpoints against the real filter chain
 * (auth, rate limit, body limits) - but with the uploader effectively
 * disabled (1 h interval) so tests only exercise the ingest path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogIngestControllerTest {

    static final String API_KEY = "qk_e2e11111222233334444555566667777";
    static final String OWNER = "jiahao.li@sigmob.com";

    @TempDir
    static Path temp;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MeterRegistry registry;

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
        registry.add("audit.rate-limit-per-ip", () -> "1000");
        registry.add("audit.close-idle-seconds", () -> "3600");
        registry.add("audit.upload-interval-seconds", () -> "3600");
        // this dev machine's data volume is ~98% full; disable disk backpressure noise here
        // (the 503 path itself is covered by DiskBackpressureTest with a mocked DiskMonitor)
        registry.add("audit.disk.high-watermark", () -> "1.0");
        registry.add("oss.mode", () -> "file");
        registry.add("oss.file-storage-dir", () -> temp.resolve("storage").toString());
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void singleRecordIsAccepted() throws Exception {
        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"log_schema\":\"1.0.1\",\"record_kind\":\"hook_event\","
                                + "\"client_id\":\"CZ-0101000193/happyelements\",\"session_id\":\"s-1\","
                                + "\"timestamp\":\"2026-09-01T02:09:12.883Z\",\"timestamp_ms\":1788228552883,"
                                + "\"event\":\"UserPromptSubmit\",\"type\":\"USER_REQUEST\",\"prompt\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.deduped").value(0));
    }

    @Test
    void poisonedSingleRecordStillAnswers200() throws Exception {
        // fire-and-forget client: >=400 would requeue forever, so poison -> 200 + rejected
        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json at all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.deduped").value(0));
    }

    @Test
    void nonObjectSingleRecordIsRejected() throws Exception {
        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2,3]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(1));
    }

    @Test
    void batchWithPoisonLineIsolatesDamage() throws Exception {
        String ndjson = "{\"client_id\":\"c1\",\"session_id\":\"s1\",\"timestamp_ms\":1,\"type\":\"USER_REQUEST\"}\n"
                + "{this line is poisoned\n"
                + "{\"client_id\":\"c1\",\"session_id\":\"s1\",\"timestamp_ms\":2,\"type\":\"TOOL_REQUEST\"}\n";
        MvcResult result = mockMvc.perform(post("/api/logs/batch")
                        .header("X-API-Key", API_KEY)
                        .contentType("application/x-ndjson")
                        .content(ndjson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.deduped").value(0))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("\"accepted\":2");
    }

    @Test
    void gzipBatchIsTransparentlyDecompressed() throws Exception {
        String ndjson = "{\"client_id\":\"c1\",\"session_id\":\"sg\",\"timestamp_ms\":10,\"type\":\"LLM_USAGE\",\"credits\":1.5}\n"
                + "{\"client_id\":\"c1\",\"session_id\":\"sg\",\"timestamp_ms\":11,\"type\":\"LLM_USAGE\",\"credits\":2.5}\n"
                + "{\"client_id\":\"c1\",\"session_id\":\"sg\",\"timestamp_ms\":12,\"type\":\"LLM_USAGE\",\"credits\":0.5}\n";
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(ndjson.getBytes(StandardCharsets.UTF_8));
        }
        mockMvc.perform(post("/api/logs/batch")
                        .header("X-API-Key", API_KEY)
                        .header("Content-Encoding", "gzip")
                        .contentType("application/x-ndjson")
                        .content(gz.toByteArray()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(3))
                .andExpect(jsonPath("$.rejected").value(0));
    }

    @Test
    void malformedGzipBatchAnswers200WithRejectedOne() throws Exception {
        // M3: a body that DECLARES gzip but is garbage is unrecoverable data;
        // answering 5xx would make the hook client requeue and retry forever
        byte[] garbage = "definitely not a gzip stream".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/logs/batch")
                        .header("X-API-Key", API_KEY)
                        .header("Content-Encoding", "gzip")
                        .contentType("application/x-ndjson")
                        .content(garbage))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.deduped").value(0));
    }

    @Test
    void malformedGzipSingleRecordAnswers200WithRejectedOne() throws Exception {
        byte[] garbage = new byte[] {0x00, 0x11, 0x22, 0x33, 0x44};
        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", API_KEY)
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(garbage))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.deduped").value(0));
    }

    @Test
    void healthReportsTheConfiguredStorageMode() throws Exception {
        // D2: ops must be able to catch a "file" deployment at a glance
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storage_mode").value("file"));
    }

    @Test
    void nonObjectSingleRecordStillCountsAsReceived() throws Exception {
        // Ryan note 3: the received counter must cover non-object payloads too,
        // keeping the endpoint=single metric consistent with the batch one
        double before = counter("records_received_total", "endpoint", "single");
        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2,3]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1));
        assertThat(counter("records_received_total", "endpoint", "single")).isEqualTo(before + 1);
    }

    @Test
    void srcFallbackMetricCountsUndeterminableRecords() throws Exception {
        // Mark m1: no session_id and no QoderWork event -> src is the qoder fallback
        double before = counter("records_src_fallback_total", null, null);
        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_id\":\"cf\",\"timestamp_ms\":789,\"type\":\"SESSION_START\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));
        assertThat(counter("records_src_fallback_total", null, null)).isGreaterThan(before);
    }

    private double counter(String name, String tagKey, String tagValue) {
        var found = tagKey == null ? registry.find(name).counter()
                : registry.find(name).tag(tagKey, tagValue).counter();
        return found == null ? 0.0 : found.count();
    }

    @Test
    void missingOrUnknownKeyIsRejectedWith401() throws Exception {
        mockMvc.perform(post("/api/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_api_key"));

        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", "qk_0000000000000000000000000000dead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_api_key"));

        // health stays open without auth
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    void oversizedDecompressedBatchAnswers413() throws Exception {
        // build a single ~9 MiB JSON line, above the 8 MiB decompressed cap
        StringBuilder pad = new StringBuilder();
        while (pad.length() < 9 * 1024 * 1024) {
            pad.append("0123456789abcdef");
        }
        byte[] body = ("{\"pad\":\"" + pad + "\"}").getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/logs/batch")
                        .header("X-API-Key", API_KEY)
                        .contentType("application/x-ndjson")
                        .content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("payload_too_large"));
    }

    @Test
    void resentRecordIsDedupedAcrossRequests() throws Exception {
        String record = "{\"client_id\":\"c9\",\"session_id\":\"sd\",\"timestamp_ms\":42,"
                + "\"type\":\"TOOL_REQUEST\",\"tool_call_id\":\"call_9\"}";
        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(record))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));

        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(record))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.deduped").value(1));
    }
}
