package com.miriyum.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StagingMapDemoStoreComposeConfigurationTest {

    @Test
    void forwardsTheStagingOnlyMapDemoFlagFailClosed() throws IOException {
        String compose = Files.readString(Path.of("../deploy/docker-compose.prod.yml"));
        String environmentExample = Files.readString(Path.of("../deploy/.env.example"));

        assertThat(compose)
                .contains("MIRIYUM_RUNTIME_ENVIRONMENT: staging")
                .contains("MIRIYUM_STAGING_MAP_DEMO_STORES_ENABLED: ${MIRIYUM_STAGING_MAP_DEMO_STORES_ENABLED:-false}");
        assertThat(environmentExample)
                .contains("MIRIYUM_STAGING_MAP_DEMO_STORES_ENABLED=false");
    }
}
