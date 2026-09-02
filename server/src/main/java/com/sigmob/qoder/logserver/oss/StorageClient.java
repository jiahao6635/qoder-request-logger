package com.sigmob.qoder.logserver.oss;

import java.io.IOException;
import java.util.List;

/**
 * Minimal storage abstraction: one implementation backed by Aliyun OSS, one by
 * the local filesystem ({@code oss.mode=file}) so the full pipeline can be
 * exercised in tests and on machines without cloud credentials.
 */
public interface StorageClient {

    /** Stores the payload under the given object key. */
    void put(String key, byte[] bytes) throws IOException;

    /** Lists object keys starting with the given prefix. */
    List<String> list(String prefix) throws IOException;
}
