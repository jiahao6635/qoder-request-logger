package com.sigmob.qoder.logserver.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Startup guard for {@code oss.mode}. A missing or misspelled value must abort
 * the boot with a clear message: silently falling back to {@code file} mode
 * (local disk, no OSS upload) is exactly the misconfiguration that would go
 * unnoticed until audit data is needed.
 *
 * <p>The check runs as a {@link BeanFactoryPostProcessor} — after every bean
 * definition (including the {@code @ConditionalOnProperty} storage clients)
 * is registered but before any regular bean is instantiated — so the failure
 * is loud, early and never races the dependency wiring of
 * {@code OssUploader}/{@code ManifestJob}.</p>
 */
@Configuration(proxyBeanMethods = false)
public class StorageModeConfig {

    static final String SUPPORTED_MODES = "'oss' or 'file'";

    @Bean
    static BeanFactoryPostProcessor storageModeValidator() {
        return new StorageModeValidator();
    }

    static final class StorageModeValidator implements BeanFactoryPostProcessor {

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            String mode = beanFactory.getBean(Environment.class).getProperty("oss.mode");
            validate(mode);
        }

        static void validate(String mode) {
            if (mode == null || mode.isBlank() || !("oss".equals(mode) || "file".equals(mode))) {
                throw new IllegalStateException(
                        "oss.mode must be " + SUPPORTED_MODES + " (got: " + mode + "); "
                                + "refusing to start: 'file' silently stores audit data only on local disk");
            }
        }
    }
}
