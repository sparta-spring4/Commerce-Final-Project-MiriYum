package com.miriyum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 컨텍스트 스모크 테스트. ADR-004의 Testcontainers MySQL 필수 기준선을 따르며 H2로 낮추지 않는다.
 * 최신 dev 인증 컨텍스트(JwtTokenProvider) 기동에 필요한 miriyum.jwt.* 설정을 명시 제공한다.
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
class MiriyumApplicationTests {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.40"));

    @Test
    void contextLoads() {
    }
}
