package com.miriyum.domain.menu.controller.storeoperator;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.MiriyumApplication;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Tag("integration-shard-c")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "spring.servlet.multipart.max-file-size=1KB",
            "spring.servlet.multipart.max-request-size=2KB",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.menu.schedule.enabled=false"
        })
class MenuImageUploadHttpIT {

    private static final String BOUNDARY = "MiriYumMultipartBoundary";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @org.springframework.test.context.DynamicPropertySource
    static void datasourceProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Test
    void fileBeyondBusinessSizeLimitReturnsMenuImageSizeError() throws Exception {
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(multipartRequest(1025), HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":\"STORE_013\"");
    }

    private HttpRequest multipartRequest(int fileSizeBytes) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/store-operators/stores/7/menus/13/images"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Idempotency-Key", "84e7a2dd-0b4f-4ee4-9cc8-e851b91e6213")
                .header("Authorization", "Bearer "
                        + jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, 11L))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(multipartBody(fileSizeBytes)))
                .build();
    }

    private byte[] multipartBody(int fileSizeBytes) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"large.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(new byte[fileSizeBytes]);
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }
}
