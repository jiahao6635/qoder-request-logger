package com.sigmob.qoder.logserver.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.sigmob.qoder.logserver.ingest.DiskMonitor;

/**
 * HTTP semantics of the disk backpressure: with a mocked (overloaded)
 * DiskMonitor the ingest endpoints must answer 503 with a Retry-After header
 * while /api/health keeps serving (reflecting the degraded state).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiskBackpressureTest {

    static final String API_KEY = "qk_backpressure0000test000000000000aa";

    @TempDir
    static Path temp;

    @Autowired
    MockMvc mockMvc;

    @MockBean
    DiskMonitor diskMonitor;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path keys = temp.resolve("api-keys.yml");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String sha = HexFormat.of().formatHex(digest.digest(API_KEY.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(keys, """
                keys:
                  - user_id: jiahao.li@sigmob.com
                    key_sha256: %s
                    enabled: true
                """.formatted(sha));
        registry.add("audit.api-keys-file", () -> keys.toString());
        registry.add("audit.spool-dir", () -> temp.resolve("spool").toString());
        registry.add("audit.upload-interval-seconds", () -> "3600");
        registry.add("oss.mode", () -> "file");
        registry.add("oss.file-storage-dir", () -> temp.resolve("storage").toString());
    }

    @Test
    void overloadedDiskRejectsIngestWith503AndRetryAfter() throws Exception {
        when(diskMonitor.usedRatio()).thenReturn(0.95);
        when(diskMonitor.isOverloaded()).thenReturn(true);
        when(diskMonitor.isWarnLevel()).thenReturn(false);

        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().longValue("Retry-After", 30))
                .andExpect(jsonPath("$.error").value("spool_overloaded"));

        mockMvc.perform(post("/api/logs/batch")
                        .header("X-API-Key", API_KEY)
                        .contentType("application/x-ndjson")
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void healthStillServesAndReflectsOverload() throws Exception {
        when(diskMonitor.usedRatio()).thenReturn(0.85);
        when(diskMonitor.isOverloaded()).thenReturn(false);
        when(diskMonitor.isWarnLevel()).thenReturn(true);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("degraded"))
                .andExpect(jsonPath("$.disk_used_ratio").value(0.85));
    }
}
