package com.sigmob.qoder.logserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code audit.*} configuration block. See application.yml for the
 * documented defaults of every property.
 */
@ConfigurationProperties(prefix = "audit")
public class ServerProperties {

    /** Path of the api-keys.yml registry file. */
    private String apiKeysFile = "./api-keys.yml";

    /** Local spool directory holding open segments and rotated parts. */
    private String spoolDir = "./spool";

    /** Maximum decompressed batch body size in MiB; beyond it the API returns 413. */
    private int maxBodyMb = 8;

    /** Fixed-window rate limit (requests per second) applied per client IP. */
    private int rateLimitPerIp = 30;

    /** Segment rotation threshold in MiB. */
    private int rotateSizeMb = 64;

    /** Segment idle-close threshold in seconds. */
    private int closeIdleSeconds = 600;

    /** Uploader scheduling interval in seconds. */
    private int uploadIntervalSeconds = 30;

    private final Disk disk = new Disk();
    private final Dedup dedup = new Dedup();

    public static class Disk {
        /**
         * Fraction of used space on the spool filesystem above which ingest is
         * rejected with 503. At 80% (watermark - 0.1) a WARN is logged.
         */
        private double highWatermark = 0.9;

        public double getHighWatermark() {
            return highWatermark;
        }

        public void setHighWatermark(double highWatermark) {
            this.highWatermark = highWatermark;
        }
    }

    public static class Dedup {
        /** Caffeine maximumSize for the idempotency cache. */
        private long maxKeys = 100000;

        /** Caffeine expireAfterWrite in hours. */
        private long ttlHours = 24;

        public long getMaxKeys() {
            return maxKeys;
        }

        public void setMaxKeys(long maxKeys) {
            this.maxKeys = maxKeys;
        }

        public long getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(long ttlHours) {
            this.ttlHours = ttlHours;
        }
    }

    public String getApiKeysFile() {
        return apiKeysFile;
    }

    public void setApiKeysFile(String apiKeysFile) {
        this.apiKeysFile = apiKeysFile;
    }

    public String getSpoolDir() {
        return spoolDir;
    }

    public void setSpoolDir(String spoolDir) {
        this.spoolDir = spoolDir;
    }

    public int getMaxBodyMb() {
        return maxBodyMb;
    }

    public void setMaxBodyMb(int maxBodyMb) {
        this.maxBodyMb = maxBodyMb;
    }

    public int getRateLimitPerIp() {
        return rateLimitPerIp;
    }

    public void setRateLimitPerIp(int rateLimitPerIp) {
        this.rateLimitPerIp = rateLimitPerIp;
    }

    public int getRotateSizeMb() {
        return rotateSizeMb;
    }

    public void setRotateSizeMb(int rotateSizeMb) {
        this.rotateSizeMb = rotateSizeMb;
    }

    public int getCloseIdleSeconds() {
        return closeIdleSeconds;
    }

    public void setCloseIdleSeconds(int closeIdleSeconds) {
        this.closeIdleSeconds = closeIdleSeconds;
    }

    public int getUploadIntervalSeconds() {
        return uploadIntervalSeconds;
    }

    public void setUploadIntervalSeconds(int uploadIntervalSeconds) {
        this.uploadIntervalSeconds = uploadIntervalSeconds;
    }

    public Disk getDisk() {
        return disk;
    }

    public Dedup getDedup() {
        return dedup;
    }
}
