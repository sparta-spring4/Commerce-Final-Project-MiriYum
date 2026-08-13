package com.miriyum.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * Jackson 역직렬화 설정 스모크. ADR-004 Testcontainers MySQL 기준선을 유지하고 H2로 낮추지 않는다.
 */
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.jwt.issuer=miriyum"
        })
@Tag("integration")
@Tag("integration-shard-d")
@Testcontainers(disabledWithoutDocker = true)
class ApplicationJacksonConfigurationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.40"));

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 알_수_없는_json_필드를_허용하지_않는다() {
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .isTrue();
    }
}
