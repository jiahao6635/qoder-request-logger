package com.sigmob.qoder.logserver.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sigmob.qoder.logserver.config.ServerProperties;

/**
 * Idempotency layer: a record seen once (identified by its dedup key) is
 * dropped on every retry. Covers both duplicates inside one batch and whole
 * batch re-sends after a 5xx, which is what makes "at-least-once client +
 * dedup server" deliver exactly-once storage.
 */
@Service
public class DedupService {

    private final Cache<String, Boolean> seen;

    @Autowired
    public DedupService(ServerProperties properties) {
        this(properties.getDedup().getMaxKeys(), properties.getDedup().getTtlHours());
    }

    public DedupService(long maxKeys, long ttlHours) {
        this.seen = Caffeine.newBuilder()
                .maximumSize(maxKeys)
                .expireAfterWrite(java.time.Duration.ofHours(ttlHours))
                .build();
    }

    /**
     * Registers a dedup key. Returns {@code true} when the record is new
     * (caller should persist it), {@code false} when it was already seen.
     */
    public boolean admit(String key) {
        return seen.asMap().putIfAbsent(key, Boolean.TRUE) == null;
    }

    /** Removes a key again, used to compensate when persisting failed after admission. */
    public void invalidate(String key) {
        seen.invalidate(key);
    }

    public long estimatedSize() {
        return seen.estimatedSize();
    }

    /**
     * Dedup key: sha256 of the pipe-joined identity fields. Missing fields
     * contribute an empty string so a re-sent record always hashes identically.
     */
    public static String dedupKey(JsonNode record) {
        String joined = text(record, "uid") + "|"
                + text(record, "session_id") + "|"
                + text(record, "timestamp_ms") + "|"
                + text(record, "type") + "|"
                + text(record, "tool_call_id") + "|"
                + text(record, "prompt_id") + "|"
                + text(record, "transcript_uuid");
        return sha256Hex(joined);
    }

    private static String text(JsonNode record, String field) {
        JsonNode node = record.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("");
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
