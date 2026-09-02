package com.sigmob.qoder.logserver.oss;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies the object key contract: the single place storage paths are built. */
class OssPathPolicyTest {

    @Test
    void buildsFullSegmentObjectKey() {
        String key = OssPathPolicy.objectKey("logs/qoder/v1", "2026-09-01", "jiahao.li@sigmob.com", "qoder",
                "part-143005-a7f3-0001.ndjson");
        assertThat(key).isEqualTo(
                "logs/qoder/v1/date=2026-09-01/user=jiahao.li@sigmob.com/src=qoder/part-143005-a7f3-0001.jsonl.gz");
    }

    @Test
    void qoderworkSourceSegment() {
        String key = OssPathPolicy.objectKey("logs/qoder/v1", "2026-09-01", "u@sigmob.com", "qoderwork",
                "part-020000-b1c2-0007.ndjson");
        assertThat(key).isEqualTo(
                "logs/qoder/v1/date=2026-09-01/user=u@sigmob.com/src=qoderwork/part-020000-b1c2-0007.jsonl.gz");
    }

    @Test
    void sanitizesUserSegmentDefensively() {
        String key = OssPathPolicy.objectKey("logs/qoder/v1", "2026-09-01", "BAD Name!@host", "qoder",
                "part-000001-0000-0001.ndjson");
        assertThat(key).contains("/user=bad_name_@host/");
    }

    @Test
    void buildsManifestKey() {
        assertThat(OssPathPolicy.manifestKey("logs/qoder/v1", "2026-09-01"))
                .isEqualTo("logs/qoder/v1/_manifest/date=2026-09-01.json.gz");
    }

    @Test
    void buildsDatePrefix() {
        assertThat(OssPathPolicy.datePrefix("logs/qoder/v1", "2026-09-01"))
                .isEqualTo("logs/qoder/v1/date=2026-09-01/");
    }

    @Test
    void extractsUserSegmentFromKey() {
        String key = "logs/qoder/v1/date=2026-09-01/user=jiahao.li@sigmob.com/src=qoder/part-1-2-3.jsonl.gz";
        assertThat(OssPathPolicy.userOfKey(key)).isEqualTo("jiahao.li@sigmob.com");
    }
}
