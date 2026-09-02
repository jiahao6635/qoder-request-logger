package com.sigmob.qoder.logserver.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * C3 regression: when SpoolWriter.append fails with an UncheckedIOException
 * (Files.createDirectories failures are wrapped unchecked), the dedup slot
 * admitted just before MUST be released again - otherwise the client retry of
 * the same record lands in dedup and the record is silently lost forever.
 *
 * <p>The failure is produced with the REAL spool writer by blocking the
 * segment directory path with a regular file: createDirectories then throws
 * FileAlreadyExistsException, which SpoolWriter wraps as UncheckedIOException.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpoolWriteFailureTest {

    static final String API_KEY = "qk_spoolfail0000000000000000000000aa";
    static final String OWNER = "jiahao.li@sigmob.com";
    static final String RECORD = "{\"client_id\":\"c3\",\"session_id\":\"sf\","
            + "\"timestamp\":\"2026-09-01T02:09:12.883Z\",\"timestamp_ms\":1788228552883,"
            + "\"type\":\"USER_REQUEST\",\"prompt\":\"spool write failure\"}";

    @TempDir
    static Path temp;

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path keys = temp.resolve("api-keys.yml");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String sha = HexFormat.of().formatHex(digest.digest(API_KEY.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(keys, """
                keys:
                  - user_id: %s
                    key_sha256: %s
                    enabled: true
                """.formatted(OWNER, sha));
        registry.add("audit.api-keys-file", () -> keys.toString());
        registry.add("audit.spool-dir", () -> temp.resolve("spool").toString());
        registry.add("audit.rate-limit-per-ip", () -> "1000");
        registry.add("audit.close-idle-seconds", () -> "3600");
        registry.add("audit.upload-interval-seconds", () -> "3600");
        registry.add("audit.disk.high-watermark", () -> "1.0");
        registry.add("oss.mode", () -> "file");
        registry.add("oss.file-storage-dir", () -> temp.resolve("storage").toString());
    }

    @Test
    void uncheckedSpoolFailureReleasesDedupSlotAndRetrySucceeds() throws Exception {
        // the record's segment dir would be {spool}/date=2026-09-01/... : block
        // that path with a regular file -> createDirectories throws
        Path blocker = temp.resolve("spool").resolve("date=2026-09-01");
        Files.createDirectories(blocker.getParent());
        Files.writeString(blocker, "in the way");

        // first delivery: 500 spool_write_failed, and the dedup slot must be
        // released despite the UncheckedIOException (not just IOException)
        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("spool_write_failed"));

        // remove the blocker: the retry of the SAME record must now be accepted
        Files.delete(blocker);
        mockMvc.perform(post("/api/logs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.deduped").value(0))
                .andExpect(jsonPath("$.rejected").value(0));
    }
}
