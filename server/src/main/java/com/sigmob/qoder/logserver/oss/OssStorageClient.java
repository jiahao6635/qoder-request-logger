package com.sigmob.qoder.logserver.oss;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;

import jakarta.annotation.PreDestroy;

/**
 * {@code oss.mode=oss} implementation backed by the Aliyun OSS SDK.
 *
 * <p>Credentials are injected exclusively through the {@code OSS_AK_ID} /
 * {@code OSS_AK_SECRET} environment variables. Server-side encryption is
 * configurable: none (default), AES256 or KMS (requires {@code oss.kms-key-id}).</p>
 */
@Component
@ConditionalOnProperty(name = "oss.mode", havingValue = "oss")
public class OssStorageClient implements StorageClient {

    private static final String HEADER_SSE_KEY_ID = "x-oss-server-side-encryption-key-id";

    private final OSS client;
    private final String bucket;
    private final String encryption;
    private final String kmsKeyId;

    @Autowired
    public OssStorageClient(@Value("${oss.endpoint}") String endpoint,
                            @Value("${oss.bucket}") String bucket,
                            @Value("${oss.encryption:none}") String encryption,
                            @Value("${oss.kms-key-id:}") String kmsKeyId) {
        this(endpoint, bucket, encryption, kmsKeyId, System.getenv("OSS_AK_ID"), System.getenv("OSS_AK_SECRET"));
    }

    public OssStorageClient(String endpoint, String bucket, String encryption, String kmsKeyId,
                            String accessKeyId, String accessKeySecret) {
        if (accessKeyId == null || accessKeyId.isBlank() || accessKeySecret == null || accessKeySecret.isBlank()) {
            throw new IllegalStateException(
                    "oss.mode=oss requires OSS_AK_ID / OSS_AK_SECRET environment variables");
        }
        if ("kms".equalsIgnoreCase(encryption) && (kmsKeyId == null || kmsKeyId.isBlank())) {
            throw new IllegalStateException("oss.encryption=kms requires oss.kms-key-id");
        }
        this.client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        this.bucket = bucket;
        this.encryption = encryption == null ? "none" : encryption.toLowerCase();
        this.kmsKeyId = kmsKeyId;
    }

    /** Releases the SDK's idle HTTP connection pool on shutdown. */
    @PreDestroy
    void close() {
        client.shutdown();
    }

    @Override
    public void put(String key, byte[] bytes) throws IOException {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        if ("aes256".equalsIgnoreCase(encryption)) {
            metadata.setServerSideEncryption(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
        } else if ("kms".equalsIgnoreCase(encryption)) {
            metadata.setServerSideEncryption(ObjectMetadata.KMS_SERVER_SIDE_ENCRYPTION);
            metadata.setHeader(HEADER_SSE_KEY_ID, kmsKeyId);
        }
        try {
            client.putObject(new PutObjectRequest(bucket, key, new java.io.ByteArrayInputStream(bytes), metadata));
        } catch (RuntimeException e) {
            throw new IOException("OSS put failed for " + key, e);
        }
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        List<String> keys = new ArrayList<>();
        String marker = null;
        try {
            while (true) {
                ListObjectsRequest request = new ListObjectsRequest(bucket);
                request.setPrefix(prefix);
                request.setMaxKeys(1000);
                if (marker != null) {
                    request.setMarker(marker);
                }
                ObjectListing listing = client.listObjects(request);
                listing.getObjectSummaries().forEach(s -> keys.add(s.getKey()));
                if (!listing.isTruncated()) {
                    return keys;
                }
                marker = listing.getNextMarker();
            }
        } catch (RuntimeException e) {
            throw new IOException("OSS list failed for prefix " + prefix, e);
        }
    }
}
