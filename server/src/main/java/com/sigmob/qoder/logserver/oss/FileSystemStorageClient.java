package com.sigmob.qoder.logserver.oss;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code oss.mode=file} implementation: objects are plain files under a local
 * root directory. Enables the full spool -> upload -> manifest pipeline to run
 * end to end on a laptop without cloud credentials.
 */
@Component
@ConditionalOnProperty(name = "oss.mode", havingValue = "file")
public class FileSystemStorageClient implements StorageClient {

    private static final Logger log = LoggerFactory.getLogger(FileSystemStorageClient.class);

    private final Path root;

    @Autowired
    public FileSystemStorageClient(@Value("${oss.file-storage-dir:./oss-storage}") String rootDir) {
        this(Path.of(rootDir));
    }

    public FileSystemStorageClient(Path root) {
        // absolute + normalized ONCE here: with a relative root like ./oss-storage the
        // raw value would never match the (dot-free) resolved child paths and every
        // put()/list() would reject its own keys
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create storage root " + this.root, e);
        }
        log.warn("STORAGE MODE IS FILE — objects will NOT be uploaded to OSS; "
                + "they are kept as plain files under {}", this.root);
    }

    @Override
    public void put(String key, byte[] bytes) throws IOException {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("illegal object key escapes storage root: " + key);
        }
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        Path prefixPath = root.resolve(prefix).normalize();
        Path scanFrom = Files.isDirectory(prefixPath) ? prefixPath : prefixPath.getParent();
        if (scanFrom == null || !Files.isDirectory(scanFrom)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(scanFrom)) {
            List<String> keys = new ArrayList<>();
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.startsWith(prefixPath))
                    .forEach(p -> keys.add(root.relativize(p).toString().replace('\\', '/')));
            keys.sort(String::compareTo);
            return keys;
        }
    }

    public Path root() {
        return root;
    }
}
