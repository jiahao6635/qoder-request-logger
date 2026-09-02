package com.sigmob.qoder.logserver.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import com.sigmob.qoder.logserver.LogServerApplication;

/**
 * D2: a missing or misspelled {@code oss.mode} must abort the startup with a
 * clear message instead of silently storing audit data on the local disk.
 */
class StorageModeConfigTest {

    @Test
    void invalidModeAbortsStartupWithClearMessage(@TempDir Path temp) throws Exception {
        Path keys = writeKeys(temp);
        // command line args: highest precedence, overrides the classpath application.yml
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(LogServerApplication.class)
                .web(WebApplicationType.NONE)
                .run("--oss.mode=bogus",
                        "--audit.api-keys-file=" + keys.toAbsolutePath(),
                        "--audit.spool-dir=" + temp.resolve("spool").toAbsolutePath(),
                        "--audit.upload-interval-seconds=3600"));

        assertThat(rootCauseOf(thrown))
                .hasMessageContaining("oss.mode must be 'oss' or 'file'")
                .hasMessageContaining("bogus");
    }

    @Test
    void blankModeAbortsStartup(@TempDir Path temp) throws Exception {
        Path keys = writeKeys(temp);
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(LogServerApplication.class)
                .web(WebApplicationType.NONE)
                .run("--oss.mode=",
                        "--audit.api-keys-file=" + keys.toAbsolutePath(),
                        "--audit.spool-dir=" + temp.resolve("spool").toAbsolutePath(),
                        "--audit.upload-interval-seconds=3600"));

        assertThat(rootCauseOf(thrown)).hasMessageContaining("oss.mode must be 'oss' or 'file'");
    }

    @Test
    void validModesPassValidation() {
        // unit-level: the guard accepts exactly the two documented modes
        StorageModeConfig.StorageModeValidator.validate("oss");
        StorageModeConfig.StorageModeValidator.validate("file");
    }

    private static Path writeKeys(Path temp) throws Exception {
        Path keys = temp.resolve("api-keys.yml");
        Files.writeString(keys, "keys: []\n");
        return keys;
    }

    private static Throwable rootCauseOf(Throwable thrown) {
        assertThat(thrown).as("startup must fail for an invalid oss.mode").isNotNull();
        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }
}
