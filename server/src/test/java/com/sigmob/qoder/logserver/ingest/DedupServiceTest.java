package com.sigmob.qoder.logserver.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Dedup semantics: identical identity -> dropped, different identity -> admitted. */
class DedupServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sameRecordIsDeduped() throws Exception {
        DedupService service = new DedupService(100, 24);
        var record = MAPPER.readTree(
                "{\"uid\":\"u-01\",\"session_id\":\"s1\",\"timestamp_ms\":1,\"type\":\"TOOL_REQUEST\"}");
        String key = DedupService.dedupKey(record);
        assertThat(service.admit(key)).isTrue();
        assertThat(service.admit(key)).isFalse();
        assertThat(service.admit(key)).isFalse();
    }

    @Test
    void differentRecordsAreAdmitted() throws Exception {
        DedupService service = new DedupService(100, 24);
        var first = MAPPER.readTree(
                "{\"uid\":\"u-01\",\"session_id\":\"s1\",\"timestamp_ms\":1,\"type\":\"TOOL_REQUEST\"}");
        var second = MAPPER.readTree(
                "{\"uid\":\"u-01\",\"session_id\":\"s1\",\"timestamp_ms\":2,\"type\":\"TOOL_REQUEST\"}");
        assertThat(service.admit(DedupService.dedupKey(first))).isTrue();
        assertThat(service.admit(DedupService.dedupKey(second))).isTrue();
    }

    @Test
    void batchInternalDuplicatesAreDeduped() throws Exception {
        // mimics the controller loop: same record delivered twice inside one batch
        DedupService service = new DedupService(100, 24);
        var record = MAPPER.readTree(
                "{\"uid\":\"u-01\",\"session_id\":\"s1\",\"timestamp_ms\":1,"
                        + "\"type\":\"TOOL_REQUEST\",\"tool_call_id\":\"call_1\"}");
        String key = DedupService.dedupKey(record);
        assertThat(service.admit(key)).isTrue();
        assertThat(service.admit(key)).isFalse();
    }

    @Test
    void invalidateAllowsReAdmission() throws Exception {
        // mimics the spool-write-failure compensation path
        DedupService service = new DedupService(100, 24);
        var record = MAPPER.readTree("{\"uid\":\"u-01\",\"session_id\":\"s1\",\"timestamp_ms\":1}");
        String key = DedupService.dedupKey(record);
        service.admit(key);
        service.invalidate(key);
        assertThat(service.admit(key)).isTrue();
    }

    @Test
    void missingFieldsContributeEmptyStrings() throws Exception {
        var sparse = MAPPER.readTree("{\"type\":\"SESSION_START\"}");
        var sparseAgain = MAPPER.readTree("{\"type\":\"SESSION_START\",\"tool_call_id\":null}");
        assertThat(DedupService.dedupKey(sparse)).isEqualTo(DedupService.dedupKey(sparseAgain));
    }
}
