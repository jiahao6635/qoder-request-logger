package com.sigmob.qoder.logserver.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies the pure derivation rules: Beijing date (+8h, including the UTC
 * 16:00-24:00 cross-day window), qoderwork source classification, timestamp
 * fallback, user sanitization and ingest stamping.
 */
class RecordNormalizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

    private static com.fasterxml.jackson.databind.JsonNode json(String content) throws Exception {
        return MAPPER.readTree(content);
    }

    @Test
    void utcCrossDayWindowMapsToNextBeijingDate() throws Exception {
        // 18:00 UTC -> 02:00 next day Beijing
        var normalized = RecordNormalizer.normalize(
                json("{\"timestamp\":\"2026-08-31T18:00:00Z\",\"session_id\":\"abc\",\"event\":\"Stop\"}"),
                "jiahao.li@sigmob.com", NOW);
        assertThat(normalized.date()).isEqualTo("2026-09-01");
    }

    @Test
    void utcExactlySixteenHoursIsNextBeijingDate() throws Exception {
        // 16:00 UTC -> exactly midnight Beijing of the next day
        var normalized = RecordNormalizer.normalize(
                json("{\"timestamp\":\"2026-08-31T16:00:00Z\"}"), "a@sigmob.com", NOW);
        assertThat(normalized.date()).isEqualTo("2026-09-01");

        // one second before stays on the same day
        var earlier = RecordNormalizer.normalize(
                json("{\"timestamp\":\"2026-08-31T15:59:59Z\"}"), "a@sigmob.com", NOW);
        assertThat(earlier.date()).isEqualTo("2026-08-31");
    }

    @Test
    void timestampMsIsUsedWhenTimestampMissing() throws Exception {
        // 1788228552883 ms = 2026-09-01T02:09:12.883Z -> Beijing 2026-09-01
        var normalized = RecordNormalizer.normalize(
                json("{\"timestamp_ms\":1788228552883}"), "a@sigmob.com", NOW);
        assertThat(normalized.date()).isEqualTo("2026-09-01");
    }

    @Test
    void serverTimeFallbackWhenBothTimestampsMissing() throws Exception {
        // NOW = 2026-09-01T03:00:00Z -> Beijing 2026-09-01 11:00
        var normalized = RecordNormalizer.normalize(json("{\"type\":\"SESSION_START\"}"), "a@sigmob.com", NOW);
        assertThat(normalized.date()).isEqualTo("2026-09-01");

        // and a NOW in the cross-day window rolls over as well
        var late = RecordNormalizer.normalize(json("{\"type\":\"SESSION_START\"}"), "a@sigmob.com",
                Instant.parse("2026-08-31T18:30:00Z"));
        assertThat(late.date()).isEqualTo("2026-09-01");
    }

    @Test
    void brokenTimestampFallsBackToTimestampMs() throws Exception {
        var normalized = RecordNormalizer.normalize(
                json("{\"timestamp\":\"not-a-date\",\"timestamp_ms\":1788228552883}"), "a@sigmob.com", NOW);
        assertThat(normalized.date()).isEqualTo("2026-09-01");
    }

    @Test
    void taskSessionPrefixRoutesToQoderwork() throws Exception {
        var normalized = RecordNormalizer.normalize(
                json("{\"session_id\":\"task-abc123\",\"timestamp\":\"2026-09-01T02:09:12.883Z\"}"),
                "a@sigmob.com", NOW);
        assertThat(normalized.src()).isEqualTo("qoderwork");
    }

    @Test
    void qoderworkLifecycleEventsRouteToQoderwork() throws Exception {
        for (String event : new String[] {"TaskCreated", "TaskCompleted"}) {
            var normalized = RecordNormalizer.normalize(
                    json("{\"session_id\":\"plain-session\",\"event\":\"" + event + "\"}"), "a@sigmob.com", NOW);
            assertThat(normalized.src()).as("event %s", event).isEqualTo("qoderwork");
        }
    }

    @Test
    void plainRecordsRouteToQoderIncludingCliProduct() throws Exception {
        var normalized = RecordNormalizer.normalize(
                json("{\"session_id\":\"plain\",\"event\":\"Stop\",\"product\":\"cli\"}"), "a@sigmob.com", NOW);
        assertThat(normalized.src()).isEqualTo("qoder");
    }

    @Test
    void srcFallbackFlaggedOnlyWhenSourceNotDerivable() throws Exception {
        // no session_id and no QoderWork event: src is the qoder FALLBACK
        var fallback = RecordNormalizer.normalize(
                json("{\"type\":\"SESSION_START\"}"), "a@sigmob.com", NOW);
        assertThat(fallback.src()).isEqualTo("qoder");
        assertThat(fallback.srcFallback()).isTrue();

        // a plain (non task-) session_id positively identifies a qoder record
        var plain = RecordNormalizer.normalize(
                json("{\"session_id\":\"abc\",\"event\":\"Stop\"}"), "a@sigmob.com", NOW);
        assertThat(plain.src()).isEqualTo("qoder");
        assertThat(plain.srcFallback()).isFalse();

        // QoderWork classifications are never a fallback
        var task = RecordNormalizer.normalize(
                json("{\"session_id\":\"task-x\",\"event\":\"TaskCreated\"}"), "a@sigmob.com", NOW);
        assertThat(task.src()).isEqualTo("qoderwork");
        assertThat(task.srcFallback()).isFalse();
        var lifecycle = RecordNormalizer.normalize(
                json("{\"event\":\"TaskCompleted\"}"), "a@sigmob.com", NOW);
        assertThat(lifecycle.src()).isEqualTo("qoderwork");
        assertThat(lifecycle.srcFallback()).isFalse();
    }

    @Test
    void userSegmentSanitization() {
        assertThat(RecordNormalizer.sanitizeUser("Jiahao.Li@Sigmob.com")).isEqualTo("jiahao.li@sigmob.com");
        assertThat(RecordNormalizer.sanitizeUser("weird user+name!#")).isEqualTo("weird_user_name__");
        assertThat(RecordNormalizer.sanitizeUser(null)).isEqualTo("unknown");
        assertThat(RecordNormalizer.sanitizeUser("")).isEqualTo("unknown");
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            longName.append('x');
        }
        assertThat(RecordNormalizer.sanitizeUser(longName.toString())).hasSize(100);
    }

    @Test
    void stampsIngestUserAndTimeAndKeepsOriginalEmail() throws Exception {
        var normalized = RecordNormalizer.normalize(
                json("{\"email\":\"li15733056635@163.com\",\"session_id\":\"s\"}"), "jiahao.li@sigmob.com", NOW);
        assertThat(normalized.stamped().path("ingest_user").asText()).isEqualTo("jiahao.li@sigmob.com");
        assertThat(normalized.stamped().path("ingest_time").asText()).isEqualTo("2026-09-01T03:00:00Z");
        assertThat(normalized.stamped().path("email").asText()).isEqualTo("li15733056635@163.com");
        assertThat(normalized.identityMismatch()).isTrue();
    }

    @Test
    void noMismatchWhenEmailMatchesOrAbsent() throws Exception {
        var matching = RecordNormalizer.normalize(json("{\"email\":\"JIAHAO.LI@sigmob.com\"}"),
                "jiahao.li@sigmob.com", NOW);
        assertThat(matching.identityMismatch()).isFalse();

        var absent = RecordNormalizer.normalize(json("{\"session_id\":\"s\"}"), "jiahao.li@sigmob.com", NOW);
        assertThat(absent.identityMismatch()).isFalse();
    }
}
