package com.sigmob.qoder.logserver.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies the pure derivation rules: Beijing date (+8h, including the UTC
 * 16:00-24:00 cross-day window), qoderwork source classification, timestamp
 * fallback, owner extraction from the payload email, user sanitization and
 * ingest stamping.
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
                NOW);
        assertThat(normalized.date()).isEqualTo("2026-09-01");
    }

    @Test
    void utcExactlySixteenHoursIsNextBeijingDate() throws Exception {
        // 16:00 UTC -> exactly midnight Beijing of the next day
        var normalized = RecordNormalizer.normalize(
                json("{\"timestamp\":\"2026-08-31T16:00:00Z\"}"), NOW);
        assertThat(normalized.date()).isEqualTo("2026-09-01");

        // one second before stays on the same day
        var earlier = RecordNormalizer.normalize(
                json("{\"timestamp\":\"2026-08-31T15:59:59Z\"}"), NOW);
        assertThat(earlier.date()).isEqualTo("2026-08-31");
    }

    @Test
    void timestampMsIsUsedWhenTimestampMissing() throws Exception {
        // 1788228552883 ms = 2026-09-01T02:09:12.883Z -> Beijing 2026-09-01
        var normalized = RecordNormalizer.normalize(
                json("{\"timestamp_ms\":1788228552883}"), NOW);
        assertThat(normalized.date()).isEqualTo("2026-09-01");
    }

    @Test
    void serverTimeFallbackWhenBothTimestampsMissing() throws Exception {
        // NOW = 2026-09-01T03:00:00Z -> Beijing 2026-09-01 11:00
        var normalized = RecordNormalizer.normalize(json("{\"type\":\"SESSION_START\"}"), NOW);
        assertThat(normalized.date()).isEqualTo("2026-09-01");

        // and a NOW in the cross-day window rolls over as well
        var late = RecordNormalizer.normalize(json("{\"type\":\"SESSION_START\"}"),
                Instant.parse("2026-08-31T18:30:00Z"));
        assertThat(late.date()).isEqualTo("2026-09-01");
    }

    @Test
    void brokenTimestampFallsBackToTimestampMs() throws Exception {
        var normalized = RecordNormalizer.normalize(
                json("{\"timestamp\":\"not-a-date\",\"timestamp_ms\":1788228552883}"), NOW);
        assertThat(normalized.date()).isEqualTo("2026-09-01");
    }

    @Test
    void taskSessionPrefixRoutesToQoderwork() throws Exception {
        var normalized = RecordNormalizer.normalize(
                json("{\"session_id\":\"task-abc123\",\"timestamp\":\"2026-09-01T02:09:12.883Z\"}"),
                NOW);
        assertThat(normalized.src()).isEqualTo("qoderwork");
    }

    @Test
    void qoderworkLifecycleEventsRouteToQoderwork() throws Exception {
        for (String event : new String[] {"TaskCreated", "TaskCompleted"}) {
            var normalized = RecordNormalizer.normalize(
                    json("{\"session_id\":\"plain-session\",\"event\":\"" + event + "\"}"), NOW);
            assertThat(normalized.src()).as("event %s", event).isEqualTo("qoderwork");
        }
    }

    @Test
    void plainRecordsRouteToQoderIncludingCliProduct() throws Exception {
        var normalized = RecordNormalizer.normalize(
                json("{\"session_id\":\"plain\",\"event\":\"Stop\",\"product\":\"cli\"}"), NOW);
        assertThat(normalized.src()).isEqualTo("qoder");
    }

    @Test
    void srcFallbackFlaggedOnlyWhenSourceNotDerivable() throws Exception {
        // no session_id and no QoderWork event: src is the qoder FALLBACK
        var fallback = RecordNormalizer.normalize(
                json("{\"type\":\"SESSION_START\"}"), NOW);
        assertThat(fallback.src()).isEqualTo("qoder");
        assertThat(fallback.srcFallback()).isTrue();

        // a plain (non task-) session_id positively identifies a qoder record
        var plain = RecordNormalizer.normalize(
                json("{\"session_id\":\"abc\",\"event\":\"Stop\"}"), NOW);
        assertThat(plain.src()).isEqualTo("qoder");
        assertThat(plain.srcFallback()).isFalse();

        // QoderWork classifications are never a fallback
        var task = RecordNormalizer.normalize(
                json("{\"session_id\":\"task-x\",\"event\":\"TaskCreated\"}"), NOW);
        assertThat(task.src()).isEqualTo("qoderwork");
        assertThat(task.srcFallback()).isFalse();
        var lifecycle = RecordNormalizer.normalize(
                json("{\"event\":\"TaskCompleted\"}"), NOW);
        assertThat(lifecycle.src()).isEqualTo("qoderwork");
        assertThat(lifecycle.srcFallback()).isFalse();
    }

    @Test
    void ownerIsExtractedFromThePayloadEmailVerbatim() throws Exception {
        var normalized = RecordNormalizer.normalize(
                json("{\"email\":\"  Jiahao.Li@Sigmob.com \"}"), NOW);
        assertThat(normalized.stamped().path("ingest_user").asText()).isEqualTo("Jiahao.Li@Sigmob.com");
        assertThat(normalized.userSegment()).isEqualTo("jiahao.li@sigmob.com");
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
    void stampsOwnerFromPayloadEmailAndKeepsOriginalEmail() throws Exception {
        var normalized = RecordNormalizer.normalize(
                json("{\"email\":\"li15733056635@163.com\",\"session_id\":\"s\"}"), NOW);
        assertThat(normalized.stamped().path("ingest_user").asText()).isEqualTo("li15733056635@163.com");
        assertThat(normalized.stamped().path("ingest_time").asText()).isEqualTo("2026-09-01T03:00:00Z");
        assertThat(normalized.stamped().path("email").asText()).isEqualTo("li15733056635@163.com");
    }

    @Test
    void emailAbsentOrBlankFallsBackToUnknownSegment() throws Exception {
        // the collector only saves records carrying an enterprise identity, so
        // a missing email means an unusual/external sender -> unknown segment
        var absent = RecordNormalizer.normalize(json("{\"session_id\":\"s\"}"), NOW);
        assertThat(absent.stamped().path("ingest_user").asText()).isEmpty();
        assertThat(absent.userSegment()).isEqualTo("unknown");

        var blank = RecordNormalizer.normalize(json("{\"email\":\"   \"}"), NOW);
        assertThat(blank.stamped().path("ingest_user").asText()).isEmpty();
        assertThat(blank.userSegment()).isEqualTo("unknown");
    }
}
