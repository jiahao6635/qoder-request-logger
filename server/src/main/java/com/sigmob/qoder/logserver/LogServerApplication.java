package com.sigmob.qoder.logserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the audit log ingestion server.
 *
 * <p>Data flow: HTTP ingest (auth -> rate limit -> parse -> stamp -> dedup ->
 * synchronous append to spool segment) followed by a scheduled uploader that
 * closes idle/oversized segments, gzips them and pushes to object storage.</p>
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class LogServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogServerApplication.class, args);
    }
}
