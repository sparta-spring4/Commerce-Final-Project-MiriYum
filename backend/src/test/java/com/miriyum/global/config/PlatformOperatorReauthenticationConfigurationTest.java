package com.miriyum.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class PlatformOperatorReauthenticationConfigurationTest {
    private static final String ENVIRONMENT_NAME = "MIRIYUM_PLATFORM_OPERATOR_REAUTH_FINGERPRINT_SECRET";

    @Test
    void dedicatedFingerprintSecretIsWiredFromApplicationThroughDeployment() throws Exception {
        var properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getFirst();
        assertThat(properties.getProperty("miriyum.platform-operator.reauthentication-fingerprint-secret"))
                .isEqualTo("${" + ENVIRONMENT_NAME + ":}");

        assertThat(Files.readString(Path.of("..", "deploy", ".env.example")))
                .contains(ENVIRONMENT_NAME + "=");
        assertThat(Files.readString(Path.of("..", "deploy", "local", ".env.example")))
                .contains(ENVIRONMENT_NAME + "=");
        assertThat(Files.readString(Path.of("..", "deploy", "docker-compose.prod.yml")))
                .contains(ENVIRONMENT_NAME + ": \"${" + ENVIRONMENT_NAME + ":?" + ENVIRONMENT_NAME
                        + " is required}\"");
        assertThat(Files.readString(Path.of("..", "deploy", "local", "docker-compose.dev.yml")))
                .contains(ENVIRONMENT_NAME + ": ${" + ENVIRONMENT_NAME + "}");
    }
}
