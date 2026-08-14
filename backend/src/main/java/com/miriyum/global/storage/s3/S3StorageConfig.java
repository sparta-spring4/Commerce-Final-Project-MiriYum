package com.miriyum.global.storage.s3;

import com.miriyum.global.storage.FileStoragePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "miriyum.storage.s3", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfig {

    @Bean
    public S3Client s3Client(S3StorageProperties properties) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new IllegalArgumentException("S3 bucket is required");
        }
        if (properties.region() == null || properties.region().isBlank()) {
            throw new IllegalArgumentException("S3 region is required");
        }
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean
    public FileStoragePort fileStoragePort(
            S3Client s3Client,
            S3StorageProperties properties
    ) {
        return new S3FileStorageAdapter(s3Client, properties.bucket());
    }
}
