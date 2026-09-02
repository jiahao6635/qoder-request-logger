package com.sigmob.qoder.logserver.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Registry loading, disabled-key rejection and mtime-driven hot reload. */
class ApiKeyRegistryTest {

    private static final String KEY_ACTIVE = "qk_aaaaaaaabbbbbbbbccccccccdddddddd";
    private static final String KEY_DISABLED = "qk_11111111222222223333333344444444";
    private static final String KEY_LATE = "qk_99999999888888887777777766666666";

    @TempDir
    Path temp;

    private Path writeKeys(String yaml) throws Exception {
        Path file = temp.resolve("api-keys.yml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void loadsEntriesAndResolvesKeys() throws Exception {
        Path file = writeKeys("""
                keys:
                  - user_id: jiahao.li@sigmob.com
                    key_sha256: %s
                    display_name: 李嘉豪
                    enabled: true
                  - user_id: felix.zhang@sigmob.com
                    key_sha256: %s
                    display_name: Felix
                    enabled: false
                """.formatted(ApiKeyRegistry.sha256Hex(KEY_ACTIVE), ApiKeyRegistry.sha256Hex(KEY_DISABLED)));
        ApiKeyRegistry registry = new ApiKeyRegistry(file);

        var active = registry.lookup(KEY_ACTIVE);
        assertThat(active).isPresent();
        assertThat(active.get().userId()).isEqualTo("jiahao.li@sigmob.com");
        assertThat(active.get().enabled()).isTrue();

        var disabled = registry.lookup(KEY_DISABLED);
        assertThat(disabled).isPresent();
        assertThat(disabled.get().enabled()).isFalse(); // the filter rejects these with 401

        assertThat(registry.lookup("qk_00000000000000000000000000000000")).isEmpty();
        assertThat(registry.lookup(null)).isEmpty();
        assertThat(registry.lookup("  ")).isEmpty();
    }

    @Test
    void missingFileFailsFast() {
        assertThatThrownBy(() -> new ApiKeyRegistry(temp.resolve("nope.yml")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hotReloadPicksUpRevocationsAndNewKeys() throws Exception {
        Path file = writeKeys("""
                keys:
                  - user_id: jiahao.li@sigmob.com
                    key_sha256: %s
                    enabled: true
                """.formatted(ApiKeyRegistry.sha256Hex(KEY_ACTIVE)));
        ApiKeyRegistry registry = new ApiKeyRegistry(file);
        assertThat(registry.lookup(KEY_ACTIVE)).isPresent();

        // rewrite with KEY_ACTIVE revoked and KEY_LATE added; bump mtime
        Files.writeString(file, """
                keys:
                  - user_id: jiahao.li@sigmob.com
                    key_sha256: %s
                    enabled: false
                  - user_id: late.arriver@sigmob.com
                    key_sha256: %s
                    enabled: true
                """.formatted(ApiKeyRegistry.sha256Hex(KEY_ACTIVE), ApiKeyRegistry.sha256Hex(KEY_LATE)));
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));

        registry.reloadIfChanged();

        var revoked = registry.lookup(KEY_ACTIVE);
        assertThat(revoked).isPresent();
        assertThat(revoked.get().enabled()).isFalse();
        assertThat(registry.lookup(KEY_LATE)).isPresent();
    }

    @Test
    void unchangedMtimeSkipsReload() throws Exception {
        Path file = writeKeys("""
                keys:
                  - user_id: jiahao.li@sigmob.com
                    key_sha256: %s
                    enabled: true
                """.formatted(ApiKeyRegistry.sha256Hex(KEY_ACTIVE)));
        ApiKeyRegistry registry = new ApiKeyRegistry(file);
        Instant loadedAt = registry.loadedAt();

        registry.reloadIfChanged(); // no mtime change -> no re-parse

        assertThat(registry.loadedAt()).isSameAs(loadedAt);
        assertThat(registry.lookup(KEY_ACTIVE)).isPresent();
    }

    @Test
    void midParseRewriteIsPickedUpOnTheNextCheck() throws Exception {
        // Ryan m3 regression: the mtime must be captured BEFORE opening the stream.
        // The registry below simulates the file being rewritten while the parser
        // still holds the (already fully read) stream: closing the stream rewrites
        // the file and bumps its mtime. With the old stat-after-parse, lastMtime
        // would already equal the NEW mtime and the rewrite would never load.
        Path file = writeKeys("""
                keys:
                  - user_id: jiahao.li@sigmob.com
                    key_sha256: %s
                    enabled: true
                """.formatted(ApiKeyRegistry.sha256Hex(KEY_ACTIVE)));
        ApiKeyRegistry registry = new ApiKeyRegistry(file) {
            @Override
            protected InputStream openStream() throws IOException {
                InputStream delegate = Files.newInputStream(file);
                return new FilterInputStream(delegate) {
                    @Override
                    public void close() throws IOException {
                        super.close(); // parser consumed the ORIGINAL content already
                        long bumped = Files.getLastModifiedTime(file).toMillis() + 10_000;
                        Files.writeString(file, """
                                keys:
                                  - user_id: late.arriver@sigmob.com
                                    key_sha256: %s
                                    enabled: true
                                """.formatted(ApiKeyRegistry.sha256Hex(KEY_LATE)));
                        Files.setLastModifiedTime(file, FileTime.fromMillis(bumped));
                    }
                };
            }
        };

        // the registry holds the ORIGINAL content and a lastMtime from BEFORE the rewrite
        assertThat(registry.lookup(KEY_ACTIVE)).isPresent();
        assertThat(registry.lookup(KEY_LATE)).isEmpty();

        // the mid-parse rewrite must be detected and loaded
        registry.reloadIfChanged();
        assertThat(registry.lookup(KEY_LATE)).isPresent();
        assertThat(registry.lookup(KEY_ACTIVE)).isEmpty();
    }

    @Test
    void globalYamlTagsAreRejectedByTheSafeConstructor() throws Exception {
        // the key file is operator-controlled but the parse must still refuse
        // arbitrary type construction (!!javax.script.ScriptEngineManager style gadgets)
        Path file = writeKeys("""
                keys:
                  - !!javax.script.ScriptEngineManager [javascript: Nashorn]
                """);
        assertThatThrownBy(() -> new ApiKeyRegistry(file))
                .isInstanceOf(RuntimeException.class);
    }
}
