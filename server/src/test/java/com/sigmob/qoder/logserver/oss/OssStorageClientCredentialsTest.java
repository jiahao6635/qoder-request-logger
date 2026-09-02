package com.sigmob.qoder.logserver.oss;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Credential-mode wiring of the OSS storage client: every invalid combination
 * must fail fast at construction with a message naming the missing variable,
 * and the network-free modes (static / sts) must build a client without
 * touching the cloud. The instance-profile happy path needs a live metadata
 * service and is covered by the deployment acceptance steps instead.
 */
class OssStorageClientCredentialsTest {

    private static final String ENDPOINT = "https://oss-cn-beijing.aliyuncs.com";

    private static OssStorageClient client(String mode, String role, String ak, String sk, String stsToken) {
        return new OssStorageClient(ENDPOINT, "bucket", "none", "", mode, role, ak, sk, stsToken);
    }

    @Test
    void staticModeRequiresBothAkAndSecret() {
        assertThatThrownBy(() -> client("static", "", null, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OSS_AK_ID");
        assertThatThrownBy(() -> client("static", "", "id", null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OSS_AK_SECRET");
    }

    @Test
    void staticModeBuildsWithoutNetwork() {
        assertThatCode(() -> client("static", "", "id", "secret", null)).doesNotThrowAnyException();
    }

    @Test
    void stsModeRequiresTokenTriple() {
        assertThatThrownBy(() -> client("sts", "", "id", "secret", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OSS_STS_TOKEN");
    }

    @Test
    void stsModeBuildsWithTokenTriple() {
        assertThatCode(() -> client("sts", "", "id", "secret", "token")).doesNotThrowAnyException();
    }

    @Test
    void instanceProfileRequiresRoleName() {
        assertThatThrownBy(() -> client("instance-profile", "", null, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OSS_INSTANCE_ROLE_NAME");
    }

    @Test
    void unknownModeFailsFast() {
        assertThatThrownBy(() -> client("whatever", "", "id", "secret", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oss.credential-mode");
    }

    @Test
    void kmsStillRequiresKeyId() {
        assertThatThrownBy(() -> new OssStorageClient(ENDPOINT, "bucket", "kms", "",
                "static", "", "id", "secret", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oss.kms-key-id");
    }
}
