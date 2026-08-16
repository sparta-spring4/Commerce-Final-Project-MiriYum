package com.miriyum.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StagingRateLimitBypassComposeConfigurationTest {

    @Test
    @DisplayName("staging Compose는 환경을 고정하고 단일 IP를 빈 기본값으로 backend에 전달한다")
    void forwardsFailClosedStagingRateLimitBypassConfiguration() throws IOException {
        String compose = Files.readString(Path.of("../deploy/docker-compose.prod.yml"));
        String environmentExample = Files.readString(Path.of("../deploy/.env.example"));

        assertThat(compose)
                .contains("MIRIYUM_RUNTIME_ENVIRONMENT: staging")
                .contains("MIRIYUM_STAGING_LOAD_TEST_SOURCE_IP: ${MIRIYUM_STAGING_LOAD_TEST_SOURCE_IP:-}");
        assertThat(environmentExample)
                .contains("MIRIYUM_STAGING_LOAD_TEST_SOURCE_IP=")
                .doesNotContain("MIRIYUM_RUNTIME_ENVIRONMENT=");
    }
}
