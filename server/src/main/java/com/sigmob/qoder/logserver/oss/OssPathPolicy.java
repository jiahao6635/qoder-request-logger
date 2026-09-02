package com.sigmob.qoder.logserver.oss;

import com.sigmob.qoder.logserver.ingest.RecordNormalizer;

/**
 * Single source of truth for every object key written to storage:
 *
 * <pre>
 * logs/qoder/v1/
 *   date=2026-09-01/user=jiahao.li@sigmob.com/src=qoder/part-143005-a7f3-0001.jsonl.gz
 *   _manifest/date=2026-09-01.json.gz
 * </pre>
 */
public final class OssPathPolicy {

    public static final String MANIFEST_DIR = "_manifest";

    private OssPathPolicy() {}

    /**
     * Builds the object key for one uploaded segment part. The user segment is
     * sanitized defensively (idempotent: records were already routed through
     * {@link RecordNormalizer#sanitizeUser}).
     */
    public static String objectKey(String prefix, String date, String user, String src, String partFileName) {
        String gzName = partFileName.endsWith(".ndjson")
                ? partFileName.substring(0, partFileName.length() - ".ndjson".length()) + ".jsonl.gz"
                : partFileName;
        return prefix + "/date=" + date + "/user=" + RecordNormalizer.sanitizeUser(user)
                + "/src=" + src + "/" + gzName;
    }

    /** Key of the daily manifest object: {@code {prefix}/_manifest/date=D.json.gz}. */
    public static String manifestKey(String prefix, String date) {
        return prefix + "/" + MANIFEST_DIR + "/date=" + date + ".json.gz";
    }

    /** Prefix listing every object of one Beijing date: {@code {prefix}/date=D/}. */
    public static String datePrefix(String prefix, String date) {
        return prefix + "/date=" + date + "/";
    }

    /** Extracts the user segment from a date/user/src object key path, for manifest grouping. */
    public static String userOfKey(String key) {
        // .../user=<segment>/src=...
        int idx = key.indexOf("user=");
        if (idx < 0) {
            return "unknown";
        }
        int end = key.indexOf("/src=", idx);
        return end < 0 ? key.substring(idx + 5) : key.substring(idx + 5, end);
    }
}
