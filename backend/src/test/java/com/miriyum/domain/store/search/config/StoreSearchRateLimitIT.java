package com.miriyum.domain.store.search.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.MiriyumApplication;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 공개 매장 조회 요청이 실제 HTTP 보안 체인과 MySQL 카운터를 거쳐 하나의 IP별 한도를
 * 공유하는지 검증한다. H2는 MySQL 전용 원자적 upsert를 지원하지 않는다.
 */
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.rate-limit.public-store-read.max-requests=2",
            "miriyum.rate-limit.public-store-read.window-seconds=60",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.menu.schedule.enabled=false"
        })
@AutoConfigureMockMvc
class StoreSearchRateLimitIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sharesOneLimitAcrossListDetailAndMenusRoutes() throws Exception {
        RequestPostProcessor ip = withRemoteAddr("10.81.0.1");

        mockMvc.perform(get("/api/v1/stores").with(ip)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/stores/999999").with(ip)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/stores/999999/menus").with(ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMMON_010"))
                .andExpect(header().string("Retry-After", Matchers.matchesPattern("[1-9][0-9]*")));
    }

    @Test
    void directFilterInputIgnoresSpoofedForwardingHeadersAndUsesRemoteAddress() throws Exception {
        RequestPostProcessor ip = withRemoteAddr("10.81.0.2");

        mockMvc.perform(get("/api/v1/stores")
                        .header("X-Real-IP", "198.51.100.1")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .with(ip))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/stores/999999")
                        .header("X-Real-IP", "198.51.100.2")
                        .header("X-Forwarded-For", "198.51.100.2").with(ip))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/stores/999999/menus")
                        .header("X-Real-IP", "198.51.100.3")
                        .header("X-Forwarded-For", "198.51.100.3").with(ip))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void doesNotCountDeniedPostRequestsTowardPublicGetLimit() throws Exception {
        RequestPostProcessor ip = withRemoteAddr("10.81.0.3");

        mockMvc.perform(post("/api/v1/stores").with(ip)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/stores").with(ip)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/stores").with(ip)).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/stores").with(ip)).andExpect(status().isOk());
    }

    private static RequestPostProcessor withRemoteAddr(String remoteAddr) {
        return request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }
}
