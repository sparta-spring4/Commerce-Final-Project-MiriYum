package com.miriyum.global.storage.s3;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("miriyum.storage.s3")
public record S3StorageProperties(
        boolean enabled,
        String bucket,
        String region
) {
}
