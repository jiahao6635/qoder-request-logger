package com.sigmob.qoder.logserver.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.sigmob.qoder.logserver.config.ServerProperties;

/**
 * Registry of employee API keys, loaded from an external YAML file.
 *
 * <p>The server only ever stores {@code sha256(raw key)}; the plaintext key
 * exists solely in the employee's hook configuration. The file is re-parsed
 * when its mtime changes (checked every 5 minutes) so revocations propagate
 * without a restart.</p>
 */
@Component
public class ApiKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRegistry.class);

    /** An authenticated key entry. */
    public record KeyEntry(String userId, String displayName, boolean enabled) {}

    private final Path file;
    private volatile Map<String, KeyEntry> entries = Map.of();
    private volatile long lastMtime = -1;
    private volatile Instant loadedAt = null;
    /** Guards reload vs. concurrent mtime checks; cheap and rarely contended. */
    private final Object reloadLock = new Object();

    @Autowired
    public ApiKeyRegistry(ServerProperties properties) {
        this(Path.of(properties.getApiKeysFile()));
    }

    public ApiKeyRegistry(Path file) {
        this.file = file;
        reload(); // fail fast at startup when the file is missing/broken
    }

    /** Resolves a raw key (e.g. {@code qk_0f...}) to its entry, if known. */
    public Optional<KeyEntry> lookup(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(sha256Hex(rawKey.trim())));
    }

    public int size() {
        return entries.size();
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    /** Re-reads the file only when its mtime changed since the last parse. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void reloadIfChanged() {
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (mtime != lastMtime) {
                synchronized (reloadLock) {
                    mtime = Files.getLastModifiedTime(file).toMillis();
                    if (mtime != lastMtime) {
                        reload();
                    }
                }
            }
        } catch (IOException e) {
            log.warn("api-keys file {} unreadable during hot-reload check, keeping previous registry", file, e);
        }
    }

    /** Parses the file. Called at startup (throws on fatal problems) and on hot reload. */
    public void reload() {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("api-keys file not found: " + file.toAbsolutePath());
        }
        // capture the mtime BEFORE opening the stream: if the file is rewritten
        // while we parse it, the changed on-disk mtime still differs from the
        // recorded one and the next check reloads it. Stat-after-parse would
        // swallow exactly that window and the rewrite would never load.
        long mtimeAtOpen;
        try {
            mtimeAtOpen = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read api-keys file: " + file.toAbsolutePath(), e);
        }
        Map<String, KeyEntry> parsed = new ConcurrentHashMap<>();
        try (InputStream in = openStream()) {
            // SafeConstructor: only plain YAML types (maps/lists/scalars) are
            // accepted; arbitrary !!global tags would deserialize attacker classes
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            if (!(root instanceof Map<?, ?> rootMap) || !(rootMap.get("keys") instanceof List<?> keyList)) {
                throw new IllegalStateException("api-keys file must contain a top-level 'keys' list: " + file);
            }
            for (Object item : keyList) {
                if (!(item instanceof Map<?, ?> entryMap)) {
                    continue;
                }
                String userId = str(entryMap.get("user_id"));
                String sha = str(entryMap.get("key_sha256")).toLowerCase(Locale.ROOT);
                String displayName = str(entryMap.get("display_name"));
                boolean enabled = !Boolean.FALSE.equals(entryMap.get("enabled"));
                if (userId.isEmpty() || !sha.matches("[0-9a-f]{64}")) {
                    log.warn("skipping malformed api-keys entry in {} (user_id={})", file, userId);
                    continue;
                }
                parsed.put(sha, new KeyEntry(userId, displayName, enabled));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read api-keys file: " + file.toAbsolutePath(), e);
        }
        this.entries = Map.copyOf(parsed);
        this.lastMtime = mtimeAtOpen;
        this.loadedAt = Instant.now();
        log.info("loaded {} api key entries from {} (enabled={})", entries.size(), file,
                entries.values().stream().filter(KeyEntry::enabled).count());
    }

    /** Opens the key file stream; protected so tests can simulate mid-parse rewrites. */
    protected InputStream openStream() throws IOException {
        return Files.newInputStream(file);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
