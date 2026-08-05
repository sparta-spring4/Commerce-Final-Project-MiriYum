package com.miriyum.domain.store.search.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.rate-limit.public-store-read.max-requests=2",
            "miriyum.rate-limit.public-store-read.window-seconds=60",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.menu.schedule.enabled=false"
        })
class StoreSearchUntrustedForwardedIpIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @LocalServerPort
    private int port;

    @org.springframework.test.context.DynamicPropertySource
    static void datasourceProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry
    ) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Test
    void untrustedDirectPeerCannotSelectRateLimitBucketWithForwardingHeaders()
            throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        assertThat(get(client, "/api/v1/stores", "198.51.100.10")).isEqualTo(200);
        assertThat(get(client, "/api/v1/stores/999999", "198.51.100.20")).isEqualTo(404);
        assertThat(get(client, "/api/v1/stores/999999/menus", "198.51.100.30"))
                .isEqualTo(429);
    }

    private int get(HttpClient client, String path, String spoofedIp) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Real-IP", spoofedIp)
                .header("X-Forwarded-For", spoofedIp)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
