package com.sigmob.qoder.logserver.oss;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.auth.InstanceProfileCredentialsProvider;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;

import jakarta.annotation.PreDestroy;

/**
 * {@code oss.mode=oss} implementation backed by the Aliyun OSS SDK.
 *
 * <p>Credentials are selected by {@code oss.credential-mode} (env
 * {@code OSS_CREDENTIAL_MODE}):
 * <ul>
 * <li>{@code static} (default) — long-lived {@code OSS_AK_ID}/{@code OSS_AK_SECRET}
 *     of the writer RAM user;</li>
 * <li>{@code sts} — explicit temporary credentials triple {@code OSS_AK_ID} /
 *     {@code OSS_AK_SECRET} / {@code OSS_STS_TOKEN} (AssumeRole output); the
 *     process never refreshes the token, so this only suits short-lived runs;</li>
 * <li>{@code instance-profile} — no AK/SK at all: STS temporary credentials are
 *     fetched from the ECS instance metadata service under the RAM role bound to
 *     the instance ({@code oss.instance-role-name} / {@code OSS_INSTANCE_ROLE_NAME})
 *     and rotated automatically by the SDK. The first fetch happens eagerly at
 *     startup so a missing role binding fails the boot instead of silently
 *     dead-lettering uploads later.</li>
 * </ul>
 * Server-side encryption is configurable: none (default), AES256 or KMS
 * (requires {@code oss.kms-key-id}).</p>
 */
@Component
@ConditionalOnProperty(name = "oss.mode", havingValue = "oss")
public class OssStorageClient implements StorageClient {

    private static final Logger log = LoggerFactory.getLogger(OssStorageClient.class);

    private static final String HEADER_SSE_KEY_ID = "x-oss-server-side-encryption-key-id";

    private final OSS client;
    private final String bucket;
    private final String encryption;
    private final String kmsKeyId;

    @Autowired
    public OssStorageClient(@Value("${oss.endpoint}") String endpoint,
                            @Value("${oss.bucket}") String bucket,
                            @Value("${oss.encryption:none}") String encryption,
                            @Value("${oss.kms-key-id:}") String kmsKeyId,
                            @Value("${oss.credential-mode:static}") String credentialMode,
                            @Value("${oss.instance-role-name:}") String instanceRoleName) {
        this(endpoint, bucket, encryption, kmsKeyId, credentialMode, instanceRoleName,
                System.getenv("OSS_AK_ID"), System.getenv("OSS_AK_SECRET"), System.getenv("OSS_STS_TOKEN"));
    }

    OssStorageClient(String endpoint, String bucket, String encryption, String kmsKeyId,
                     String credentialMode, String instanceRoleName,
                     String accessKeyId, String accessKeySecret, String stsToken) {
        if ("kms".equalsIgnoreCase(encryption) && (kmsKeyId == null || kmsKeyId.isBlank())) {
            throw new IllegalStateException("oss.encryption=kms requires oss.kms-key-id");
        }
        CredentialsProvider provider = credentialsProvider(credentialMode, instanceRoleName,
                accessKeyId, accessKeySecret, stsToken);
        this.client = new OSSClientBuilder().build(endpoint, provider);
        if (provider instanceof InstanceProfileCredentialsProvider instanceProvider) {
            // eager first fetch: a missing RAM role binding must fail the boot now,
            // not surface 5 upload retries later as dead-lettered audit data
            try {
                instanceProvider.getCredentials();
                log.info("instance-profile STS credentials fetched for role {} "
                        + "(auto-rotated by the SDK before expiry)", instanceRoleName);
            } catch (RuntimeException e) {
                client.shutdown();
                throw new IllegalStateException("cannot fetch STS credentials from the ECS instance "
                        + "metadata service for role " + instanceRoleName
                        + " (check the RAM role binding on the instance and metadata service reachability)", e);
            }
        }
        this.bucket = bucket;
        this.encryption = encryption == null ? "none" : encryption.toLowerCase();
        this.kmsKeyId = kmsKeyId;
    }

    /** Selects the credential provider for the configured {@code oss.credential-mode}. */
    private static CredentialsProvider credentialsProvider(String credentialMode, String instanceRoleName,
                                                           String accessKeyId, String accessKeySecret,
                                                           String stsToken) {
        String mode = credentialMode == null || credentialMode.isBlank()
                ? "static" : credentialMode.trim().toLowerCase();
        return switch (mode) {
            case "static" -> {
                if (accessKeyId == null || accessKeyId.isBlank()) {
                    throw new IllegalStateException("oss.credential-mode=static requires the OSS_AK_ID "
                            + "environment variable");
                }
                if (accessKeySecret == null || accessKeySecret.isBlank()) {
                    throw new IllegalStateException("oss.credential-mode=static requires the OSS_AK_SECRET "
                            + "environment variable");
                }
                yield new DefaultCredentialProvider(accessKeyId, accessKeySecret);
            }
            case "sts" -> {
                if (accessKeyId == null || accessKeyId.isBlank()
                        || accessKeySecret == null || accessKeySecret.isBlank()
                        || stsToken == null || stsToken.isBlank()) {
                    throw new IllegalStateException("oss.credential-mode=sts requires OSS_AK_ID, OSS_AK_SECRET "
                            + "and OSS_STS_TOKEN (temporary AssumeRole credentials)");
                }
                yield new DefaultCredentialProvider(accessKeyId, accessKeySecret, stsToken);
            }
            case "instance-profile" -> {
                if (instanceRoleName == null || instanceRoleName.isBlank()) {
                    throw new IllegalStateException("oss.credential-mode=instance-profile requires "
                            + "oss.instance-role-name (OSS_INSTANCE_ROLE_NAME): the RAM role bound to the ECS instance");
                }
                yield new InstanceProfileCredentialsProvider(instanceRoleName);
            }
            default -> throw new IllegalStateException(
                    "oss.credential-mode must be static, sts or instance-profile (got: " + mode + ")");
        };
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
