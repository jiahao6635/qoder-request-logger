package com.sigmob.qoder.logserver.oss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C1 regression: a RELATIVE storage root (the {@code ./oss-storage} default)
 * must behave exactly like an absolute one - put/list/escape checks all rely
 * on the root being comparable to the resolved child paths.
 */
class FileSystemStorageClientTest {

    @Test
    void relativeRootPutListAndOnDiskLocation() throws Exception {
        // relative to the process CWD, exactly like the default ./oss-storage config
        Path relativeRoot = Path.of("./oss-storage-test-c1");
        FileSystemStorageClient client = new FileSystemStorageClient(relativeRoot);
        try {
            assertThat(client.root()).isEqualTo(relativeRoot.toAbsolutePath().normalize());

            String key = "logs/qoder/v1/date=2026-09-01/user=u@sigmob.com/src=qoder/part-000001-aaaa-0001.ndjson";
            client.put(key, "hello".getBytes(StandardCharsets.UTF_8));

            assertThat(client.list("logs/qoder/v1/")).containsExactly(key);
            assertThat(client.list("logs/qoder/v1/date=2026-09-01/user=u@sigmob.com/src=qoder/"))
                    .containsExactly(key);

            // the object really lands under the ABSOLUTE location of the relative root
            Path expectedFile = relativeRoot.toAbsolutePath().normalize()
                    .resolve("logs/qoder/v1/date=2026-09-01/user=u@sigmob.com/src=qoder/part-000001-aaaa-0001.ndjson");
            assertThat(expectedFile).hasContent("hello");
        } finally {
            deleteRecursively(client.root());
        }
    }

    @Test
    void absoluteRootStillWorksAndRejectsEscapingKeys(@TempDir Path temp) throws Exception {
        FileSystemStorageClient client = new FileSystemStorageClient(temp.resolve("storage"));
        client.put("a/b.ndjson", "x".getBytes(StandardCharsets.UTF_8));
        assertThat(client.list("a/")).containsExactly("a/b.ndjson");
        assertThatThrownBy(() -> client.put("../escape.ndjson", "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes storage root");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best effort cleanup of the test's own directory
                }
            });
        }
    }
}
