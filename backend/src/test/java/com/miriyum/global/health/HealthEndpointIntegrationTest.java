package com.miriyum.global.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 배포용 health probe가 인증 없이 접근되고 실제 MySQL 컨테이너에서 애플리케이션 준비 상태를
 * 반환하는지 검증한다.
 */
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes")
class HealthEndpointIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.40");

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("인증 없이 health를 호출하면 UP 상태를 반환한다")
    void healthEndpointIsPublicAndReportsUp() throws Exception {
        // given
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/actuator/health"))
                .GET()
                .build();

        // when
        HttpResponse<String> response;
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
