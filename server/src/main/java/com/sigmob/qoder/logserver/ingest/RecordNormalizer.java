package com.sigmob.qoder.logserver.ingest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure functions deriving every routing/stamping decision from a raw record.
 *
 * <p>Responsibilities: Beijing date derivation (record timestamp, +8h), source
 * classification (qoder vs qoderwork), OSS user-segment sanitization and the
 * ingest stamps ({@code ingest_user}, {@code ingest_time}). The record owner
 * is the client-declared enterprise {@code email} (schema 1.1.0 flattens it
 * onto every saved record; records without one are never collected) - the
 * shared API key only authenticates the request, it does not attribute it.
 * Stateless and side effect free, so it is trivially testable.</p>
 */
public final class RecordNormalizer {

    public static final String FIELD_INGEST_USER = "ingest_user";
    public static final String FIELD_INGEST_TIME = "ingest_time";

    /** Events only emitted by QoderWork long-running tasks. */
    private static final Set<String> QODERWORK_EVENTS = Set.of("TaskCreated", "TaskCompleted");

    /** OSS path user segment charset; everything else becomes an underscore. */
    private static final String USER_SEGMENT_ALLOWED = "abcdefghijklmnopqrstuvwxyz0123456789._@-";

    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private RecordNormalizer() {}

    /** Normalized view of one record, ready for spool routing. */
    public record Normalized(ObjectNode stamped, String date, String src, String userSegment,
                             boolean srcFallback) {}

    /**
     * Stamps the record with owner + server time and derives routing fields.
     *
     * @param record raw JSON object from the client (never modified in place);
     *               the owner is derived from its top-level {@code email}
     * @param now    server time used for {@code ingest_time} and timestamp fallback
     */
    public static Normalized normalize(JsonNode record, Instant now) {
        if (record == null || !record.isObject()) {
            throw new IllegalArgumentException("record must be a JSON object");
        }
        ObjectNode stamped = ((ObjectNode) record).deepCopy();
        Instant recordTime = parseTimestamp(record).orElse(now);
        String date = LocalDate.ofInstant(recordTime, SHANGHAI).toString();
        String src = deriveSrc(record);
        String owner = extractOwner(record);
        String userSegment = sanitizeUser(owner);

        stamped.put(FIELD_INGEST_USER, owner == null ? "" : owner);
        stamped.put(FIELD_INGEST_TIME, now.toString());
        return new Normalized(stamped, date, src, userSegment, srcFallback(record));
    }

    /**
     * Record time preference: textual {@code timestamp} (ISO-8601 UTC), then
     * numeric {@code timestamp_ms}; empty when both are absent/unparseable.
     */
    public static java.util.Optional<Instant> parseTimestamp(JsonNode record) {
        JsonNode ts = record.get("timestamp");
        if (ts != null && ts.isTextual()) {
            try {
                return java.util.Optional.of(Instant.parse(ts.asText().trim()));
            } catch (DateTimeParseException ignore) {
                // fall through to timestamp_ms
            }
        }
        JsonNode ms = record.get("timestamp_ms");
        if (ms != null && ms.isNumber() && ms.canConvertToLong()) {
            return java.util.Optional.of(Instant.ofEpochMilli(ms.asLong()));
        }
        return java.util.Optional.empty();
    }

    /**
     * Source classification: QoderWork long task sessions (session_id prefix
     * {@code task-} or QoderWork-only lifecycle events) go to {@code qoderwork},
     * everything else to {@code qoder}.
     */
    public static String deriveSrc(JsonNode record) {
        JsonNode sessionId = record.get("session_id");
        if (sessionId != null && sessionId.isTextual() && sessionId.asText().startsWith("task-")) {
            return "qoderwork";
        }
        JsonNode event = record.get("event");
        if (event != null && event.isTextual() && QODERWORK_EVENTS.contains(event.asText())) {
            return "qoderwork";
        }
        return "qoder";
    }

    /**
     * True when the source could not be positively derived: no session_id
     * (neither a plain nor a task- one) and no QoderWork-only lifecycle event,
     * so {@code src} is the plain-qoder fallback rather than a classification.
     * Feed to {@code records_src_fallback_total} to keep an eye on records that
     * still need a classification rule.
     */
    public static boolean srcFallback(JsonNode record) {
        JsonNode sessionId = record.get("session_id");
        if (sessionId != null && sessionId.isTextual() && !sessionId.asText().isBlank()) {
            return false; // any non-task session positively identifies a qoder record
        }
        JsonNode event = record.get("event");
        return !(event != null && event.isTextual() && QODERWORK_EVENTS.contains(event.asText()));
    }

    /**
     * Sanitizes an owner email for the OSS path segment: lowercase, characters
     * outside {@code [a-z0-9._@-]} replaced with underscore, capped at 100 chars.
     */
    public static String sanitizeUser(String owner) {
        String lowered = owner == null ? "" : owner.trim().toLowerCase(Locale.ROOT);
        if (lowered.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(lowered.length());
        for (int i = 0; i < lowered.length() && sb.length() < 100; i++) {
            char c = lowered.charAt(i);
            sb.append(USER_SEGMENT_ALLOWED.indexOf(c) >= 0 ? c : '_');
        }
        return sb.toString();
    }

    /**
     * Record owner: the client-declared enterprise {@code email} (trimmed,
     * non-empty). The collector only saves records that carry an enterprise
     * identity, so {@code null} here means an unusual/external sender and the
     * record lands in the {@code unknown} user segment.
     */
    public static String extractOwner(JsonNode record) {
        JsonNode email = record.get("email");
        if (email == null || !email.isTextual()) {
            return null;
        }
        String claimed = email.asText().trim();
        return claimed.isEmpty() ? null : claimed;
    }
}
