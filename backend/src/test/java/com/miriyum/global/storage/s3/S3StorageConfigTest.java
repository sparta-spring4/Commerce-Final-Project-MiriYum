package com.miriyum.global.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.global.storage.FileStoragePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class S3StorageConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(S3StorageConfig.class)
            .withPropertyValues(
                    "miriyum.storage.s3.bucket=miriyum-production-bucket",
                    "miriyum.storage.s3.region=ap-northeast-2"
            );

    @Test
    @DisplayName("S3 저장 기능을 끄면 S3 클라이언트와 저장 포트가 생성되지 않는다")
    void doesNotCreateBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("miriyum.storage.s3.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(S3StorageProperties.class);
                    assertThat(context).doesNotHaveBean(FileStoragePort.class);
                });
    }

    @Test
    @DisplayName("S3 저장 기능을 켜면 저장 포트가 생성된다")
    void createsStoragePortWhenEnabled() {
        contextRunner
                .withPropertyValues("miriyum.storage.s3.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(S3StorageProperties.class);
                    assertThat(context).hasSingleBean(FileStoragePort.class);
                    assertThat(context).hasSingleBean(S3FileStorageAdapter.class);
                });
    }
}
